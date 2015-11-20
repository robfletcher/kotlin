/*
 * Copyright 2010-2015 JetBrains s.r.o.
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

package org.jetbrains.kotlin.resolve.calls.tower

import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.incremental.components.LookupLocation
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowValue
import org.jetbrains.kotlin.resolve.scopes.LexicalScope
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue
import org.jetbrains.kotlin.types.KotlinType

interface ScopeTower {
    /**
     * Adds receivers to the list in order of locality, so that the closest (the most local) receiver goes first
     * Doesn't include receivers with error types
     */
    val implicitReceivers: List<ReceiverValue>

    val lexicalScope: LexicalScope

    val location: LookupLocation

    val dataFlowInfo: DataFlowDecorator

    // The closest (the most local) levels goes first
    val levels: Sequence<ScopeTowerLevel>
}

interface DataFlowDecorator {
    fun getDataFlowValue(receiver: ReceiverValue): DataFlowValue

    fun isStableReceiver(receiver: ReceiverValue): Boolean

    // doesn't include receiver.type
    fun getSmartCastTypes(receiver: ReceiverValue): Set<KotlinType>
}

interface ScopeTowerLevel {
    fun getVariables(name: Name): Collection<CandidateWithBoundDispatchReceiver<VariableDescriptor>>

    fun getFunctions(name: Name): Collection<CandidateWithBoundDispatchReceiver<FunctionDescriptor>>
}

interface CandidateWithBoundDispatchReceiver<out D : CallableDescriptor> {
    val descriptor: D

    val diagnostics: List<ResolutionDiagnostic>

    val dispatchReceiver: ReceiverValue?
}

class ResolutionCandidateStatus(val diagnostics: List<ResolutionDiagnostic>) {
    val resultingApplicability: ResolutionCandidateApplicability = diagnostics.asSequence().map { it.candidateLevel }.max()
                                                                   ?: ResolutionCandidateApplicability.RESOLVED
}

enum class ResolutionCandidateApplicability {
    RESOLVED, // call success or has uncompleted inference or in other words possible successful candidate
    RESOLVED_SYNTHESIZED,
    CONVENTION_ERROR, // missing infix, operator etc
    MAY_THROW_RUNTIME_ERROR, // unsafe call or unstable smart cast
    RUNTIME_ERROR, // problems with visibility
    IMPOSSIBLE_TO_GENERATE, // access to outer class from nested
    INAPPLICABLE // arguments not matched
    // todo wrong receiver
}

abstract class ResolutionDiagnostic(val candidateLevel: ResolutionCandidateApplicability)

// todo error for this access from nested class
class VisibilityError(val invisibleMember: DeclarationDescriptorWithVisibility): ResolutionDiagnostic(ResolutionCandidateApplicability.RUNTIME_ERROR)
class NestedClassViaInstanceReference(val classDescriptor: ClassDescriptor): ResolutionDiagnostic(ResolutionCandidateApplicability.IMPOSSIBLE_TO_GENERATE)
class InnerClassViaStaticReference(val classDescriptor: ClassDescriptor): ResolutionDiagnostic(ResolutionCandidateApplicability.IMPOSSIBLE_TO_GENERATE)
class UnsupportedInnerClassCall(val message: String): ResolutionDiagnostic(ResolutionCandidateApplicability.IMPOSSIBLE_TO_GENERATE)
class UsedSmartCastForDispatchReceiver(val smartCastType: KotlinType): ResolutionDiagnostic(ResolutionCandidateApplicability.RESOLVED_SYNTHESIZED)

object ErrorDescriptorDiagnostic : ResolutionDiagnostic(ResolutionCandidateApplicability.INAPPLICABLE)
object SynthesizedDescriptorDiagnostic: ResolutionDiagnostic(ResolutionCandidateApplicability.RESOLVED_SYNTHESIZED)
object UnstableSmartCastDiagnostic: ResolutionDiagnostic(ResolutionCandidateApplicability.MAY_THROW_RUNTIME_ERROR)

