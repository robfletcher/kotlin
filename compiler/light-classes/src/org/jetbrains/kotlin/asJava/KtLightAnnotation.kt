/*
 * Copyright 2010-2016 JetBrains s.r.o.
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

package org.jetbrains.kotlin.asJava

import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry
import org.jetbrains.kotlin.psi.KtAnnotationEntry

class KtLightAnnotation(
        private val delegate: PsiAnnotation,
        private val originalElement: KtAnnotationEntry?,
        private val owner: PsiAnnotationOwner
) : PsiAnnotation by delegate, KtLightElement<KtAnnotationEntry, PsiAnnotation> {
    private class AnnotationMemberValueWrapper(private val delegate: PsiAnnotationMemberValue) : PsiAnnotationMemberValue by delegate {
        override fun getReference() = references.singleOrNull()
        override fun getReferences() = ReferenceProvidersRegistry.getReferencesFromProviders(delegate, PsiReferenceService.Hints.NO_HINTS)
    }

    override fun getDelegate() = delegate
    override fun getOrigin() = originalElement

    override fun getName() = null
    override fun setName(newName: String) = throw UnsupportedOperationException()

    override fun getOwner() = owner

    override fun findAttributeValue(name: String?): PsiAnnotationMemberValue? {
        return AnnotationMemberValueWrapper(delegate.findAttributeValue(name) ?: return null)
    }

    override fun findDeclaredAttributeValue(name: String?): PsiAnnotationMemberValue? {
        return AnnotationMemberValueWrapper(delegate.findDeclaredAttributeValue(name) ?: return null)
    }

    override fun getText() = originalElement?.text ?: ""
    override fun getTextRange() = originalElement?.textRange ?: TextRange.EMPTY_RANGE

    override fun getParent() = owner as? PsiElement

    override fun toString() = "@$qualifiedName"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other?.javaClass != javaClass) return false
        return originalElement == (other as KtLightAnnotation).originalElement
    }

    override fun hashCode() = originalElement?.hashCode() ?: 0
}
