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

package org.jetbrains.kotlin.codegen.state

import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor
import org.jetbrains.kotlin.descriptors.impl.ClassDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.TypeParameterDescriptorImpl
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.utils.keysToMap
import java.util.*

class ReceiverTypeAndTypeParameters(val receiverType: KotlinType, val typeParameters: List<TypeParameterDescriptor>)

fun patchTypeParametersForDefaultImplMethod(function: CallableMemberDescriptor): ReceiverTypeAndTypeParameters {
    val classDescriptor = function.containingDeclaration as ClassDescriptor

    val functionTypeParameters = function.typeParameters.keysToMap { it.name.asString() }
    val interfaceTypeParameters = classDescriptor.declaredTypeParameters
    val interfaceTypeParameters2Name = interfaceTypeParameters.keysToMap { it.name.asString() }
    val conflict = LinkedHashMap(interfaceTypeParameters2Name)
    conflict.values.retainAll(functionTypeParameters.values)

    if (conflict.isNotEmpty()) {
        val functionTypeParameterNames = function.typeParameters.map { it.name.asString() }.toMutableSet()

        val mappingForInterfaceTypeParameters = conflict.keys.associateBy ({ it }) {
            typeParameter ->

            var newNamePrefix = typeParameter.name.asString() + "_I"
            var newName = newNamePrefix + generateSequence(1) { x -> x + 1 }.first { index -> newNamePrefix + index !in functionTypeParameterNames }
            functionTypeParameterNames.add(newName)
            val newTypeParameter = function.createTypeParameterWithNewName(typeParameter, newName)
            TypeProjectionImpl(newTypeParameter.defaultType)
        }

        val substitution = TypeConstructorSubstitution.createByParametersMap(mappingForInterfaceTypeParameters)
        val substitutor = TypeSubstitutor.create(substitution)

        val additionalTypeParameters = interfaceTypeParameters.map { typeParameter ->
            mappingForInterfaceTypeParameters[typeParameter]?.type?.constructor?.declarationDescriptor as? TypeParameterDescriptor ?: typeParameter
        }
        var resultTypeParameters = mutableListOf<TypeParameterDescriptor>()
        DescriptorSubstitutor.substituteTypeParameters(additionalTypeParameters, substitution, classDescriptor, resultTypeParameters)

        return ReceiverTypeAndTypeParameters(substitutor.substitute(classDescriptor.defaultType, Variance.INVARIANT)!!, resultTypeParameters)
    }

    return ReceiverTypeAndTypeParameters(classDescriptor.defaultType, interfaceTypeParameters)
}

fun CallableMemberDescriptor.createTypeParameterWithNewName(descriptor: TypeParameterDescriptor, newName: String): TypeParameterDescriptorImpl {
    val newDescriptor = TypeParameterDescriptorImpl.createForFurtherModification(
            this,
            descriptor.annotations,
            descriptor.isReified,
            descriptor.variance,
            Name.identifier(newName),
            descriptor.index,
            descriptor.source)
    descriptor.upperBounds.forEach {
        newDescriptor.addUpperBound(it)
    }
    newDescriptor.setInitialized()
    return newDescriptor
}