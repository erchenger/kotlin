/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.asJava.classes

import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Comparing
import com.intellij.psi.*
import com.intellij.psi.impl.light.LightEmptyImplementsList
import com.intellij.psi.impl.light.LightModifierList
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.containers.SLRUCache
import org.jetbrains.annotations.NonNls
import org.jetbrains.kotlin.asJava.builder.LightClassDataHolder
import org.jetbrains.kotlin.asJava.builder.LightClassDataProviderForScript
import org.jetbrains.kotlin.asJava.elements.FakeFileForLightClass
import org.jetbrains.kotlin.asJava.hasInterfaceDefaultImpls
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.load.java.structure.LightClassOriginKind
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import java.util.ArrayList
import javax.swing.Icon

class KtLightClassForScript private constructor(
        manager: PsiManager,
        private val scriptClassFqName: FqName,
        private val lightClassDataCache: CachedValue<LightClassDataHolder.ForScript>,
        val file: KtFile
) : KtLazyLightClass(manager) {
    private data class StubCacheKey(val fqName: FqName, val searchScope: GlobalSearchScope)

    class ScriptStubCache(private val project: Project) {
        private inner class ScriptCacheData {
            val cache = object : SLRUCache<StubCacheKey, CachedValue<LightClassDataHolder.ForScript>>(20, 30) {
                override fun createValue(key: StubCacheKey): CachedValue<LightClassDataHolder.ForScript> {
                    val stubProvider = LightClassDataProviderForScript.ByProjectSource(project, key.fqName, key.searchScope)
                    return CachedValuesManager.getManager(project)
                            .createCachedValue<LightClassDataHolder.ForScript>(stubProvider,false)
                }
            }
        }

        private val cachedValue: CachedValue<ScriptCacheData> = CachedValuesManager.getManager(project).createCachedValue<ScriptCacheData>(
                { CachedValueProvider.Result.create(ScriptCacheData(), PsiModificationTracker.OUT_OF_CODE_BLOCK_MODIFICATION_COUNT) },
                false)

        operator fun get(qualifiedName: FqName, searchScope: GlobalSearchScope): CachedValue<LightClassDataHolder.ForScript> {
            synchronized (cachedValue) {
                return cachedValue.value.cache.get(StubCacheKey(qualifiedName, searchScope))
            }
        }

        companion object {
            fun getInstance(project: Project): ScriptStubCache =
                    ServiceManager.getService<ScriptStubCache>(project, ScriptStubCache::class.java)
        }
    }

    private val hashCode: Int = computeHashCode()

    private val packageFqName: FqName = scriptClassFqName.parent()

    private val modifierList: PsiModifierList = LightModifierList(manager, KotlinLanguage.INSTANCE, PsiModifier.PUBLIC, PsiModifier.FINAL)

    private val implementsList: LightEmptyImplementsList = LightEmptyImplementsList(manager)

    private val packageClsFile = FakeFileForLightClass(
            file,
            lightClass = { this },
            stub = { lightClassDataCache.value.javaFileStub },
            packageFqName = packageFqName)

    override val kotlinOrigin: KtClassOrObject? get() = null

    val fqName: FqName = scriptClassFqName

    override fun getModifierList() = modifierList

    override fun hasModifierProperty(@NonNls name: String) = modifierList.hasModifierProperty(name)

    override fun isDeprecated() = false

    override fun isInterface() = false

    override fun isAnnotationType() = false

    override fun isEnum() = false

    override fun getContainingClass() = null

    override fun getContainingFile() = packageClsFile

    override fun hasTypeParameters() = false

    override fun getTypeParameters(): Array<PsiTypeParameter> = PsiTypeParameter.EMPTY_ARRAY

    override fun getTypeParameterList() = null

    override fun getDocComment() = null

    override fun getImplementsList() = implementsList

    override fun getImplementsListTypes(): Array<PsiClassType> = PsiClassType.EMPTY_ARRAY

    override fun getInterfaces(): Array<PsiClass> = PsiClass.EMPTY_ARRAY

    override fun getOwnInnerClasses(): List<PsiClass> {
        return file.script!!.declarations.filterIsInstance<KtClassOrObject>()
                // workaround for ClassInnerStuffCache not supporting classes with null names, see KT-13927
                // inner classes with null names can't be searched for and can't be used from java anyway
                // we can't prohibit creating light classes with null names either since they can contain members
                .filter { it.name != null }
                .mapNotNull { KtLightClassForSourceDeclaration.create(it) }
    }

    override fun getInitializers(): Array<PsiClassInitializer> = PsiClassInitializer.EMPTY_ARRAY

    override fun getName() = scriptClassFqName.shortName().asString()

    override fun getQualifiedName() = scriptClassFqName.asString()

    override fun isValid() = file.isValid && file.isScript && scriptClassFqName == file.script?.fqName

    override fun copy() = KtLightClassForScript(manager, scriptClassFqName, lightClassDataCache, file)

    override val lightClassData = lightClassDataCache.value.findDataForScript(scriptClassFqName)

    override fun getNavigationElement() = file

    override fun isEquivalentTo(another: PsiElement?): Boolean =
            another is PsiClass && Comparing.equal(another.qualifiedName, qualifiedName)

    override fun getElementIcon(flags: Int): Icon? = throw UnsupportedOperationException("This should be done by JetIconProvider")

    override fun hashCode() = hashCode

    private fun computeHashCode(): Int {
        var result = manager.hashCode()
        result = 31 * result + file.hashCode()
        result = 31 * result + scriptClassFqName.hashCode()
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (other == null || this::class.java != other::class.java) {
            return false
        }

        val lightClass = other as KtLightClassForScript
        if (this === other) return true

        if (this.hashCode != lightClass.hashCode) return false
        if (manager != lightClass.manager) return false
        if (file != lightClass.file) return false
        if (scriptClassFqName != lightClass.scriptClassFqName) return false

        return true
    }

    override fun toString() = "${KtLightClassForScript::class.java.simpleName}:$scriptClassFqName"

    companion object Factory {
        fun createForScript(
                manager: PsiManager,
                classFqName: FqName,
                searchScope: GlobalSearchScope,
                file: KtFile
        ): KtLightClassForScript {
            val lightClassDataCache = ScriptStubCache.getInstance(manager.project)[classFqName, searchScope]
            return KtLightClassForScript(manager, classFqName, lightClassDataCache, file)
        }
    }

    override val originKind: LightClassOriginKind
        get() = LightClassOriginKind.SOURCE
}
