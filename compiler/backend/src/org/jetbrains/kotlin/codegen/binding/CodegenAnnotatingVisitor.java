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

package org.jetbrains.kotlin.codegen.binding;

import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import com.intellij.util.containers.Stack;
import kotlin.jvm.functions.Function1;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.cfg.WhenChecker;
import org.jetbrains.kotlin.codegen.*;
import org.jetbrains.kotlin.codegen.state.GenerationState;
import org.jetbrains.kotlin.codegen.when.SwitchCodegenUtil;
import org.jetbrains.kotlin.codegen.when.WhenByEnumsMapping;
import org.jetbrains.kotlin.descriptors.*;
import org.jetbrains.kotlin.descriptors.impl.ClassDescriptorImpl;
import org.jetbrains.kotlin.fileClasses.FileClasses;
import org.jetbrains.kotlin.fileClasses.JvmFileClassesProvider;
import org.jetbrains.kotlin.load.java.descriptors.SamConstructorDescriptor;
import org.jetbrains.kotlin.name.Name;
import org.jetbrains.kotlin.psi.*;
import org.jetbrains.kotlin.resolve.BindingContext;
import org.jetbrains.kotlin.resolve.BindingTrace;
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils;
import org.jetbrains.kotlin.resolve.DescriptorUtils;
import org.jetbrains.kotlin.resolve.calls.callUtil.CallUtilKt;
import org.jetbrains.kotlin.resolve.calls.model.ExpressionValueArgument;
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall;
import org.jetbrains.kotlin.resolve.calls.model.ResolvedValueArgument;
import org.jetbrains.kotlin.resolve.constants.ConstantValue;
import org.jetbrains.kotlin.resolve.constants.EnumValue;
import org.jetbrains.kotlin.resolve.constants.NullValue;
import org.jetbrains.kotlin.resolve.scopes.MemberScope;
import org.jetbrains.kotlin.resolve.source.KotlinSourceElementKt;
import org.jetbrains.kotlin.types.KotlinType;
import org.jetbrains.org.objectweb.asm.Type;

import java.util.*;

import static org.jetbrains.kotlin.codegen.binding.CodegenBinding.*;
import static org.jetbrains.kotlin.lexer.KtTokens.*;
import static org.jetbrains.kotlin.name.SpecialNames.safeIdentifier;
import static org.jetbrains.kotlin.resolve.BindingContext.*;

class CodegenAnnotatingVisitor extends KtVisitorVoid {
    private static final TokenSet BINARY_OPERATIONS = TokenSet.orSet(
            AUGMENTED_ASSIGNMENTS,
            TokenSet.create(PLUS, MINUS, MUL, DIV, PERC, RANGE, LT, GT, LTEQ, GTEQ, IDENTIFIER)
    );

    private final Map<String, Integer> anonymousSubclassesCount = new HashMap<String, Integer>();

    private final Stack<ClassDescriptor> classStack = new Stack<ClassDescriptor>();
    private final Stack<String> nameStack = new Stack<String>();

    private final BindingTrace bindingTrace;
    private final BindingContext bindingContext;
    private final GenerationState.GenerateClassFilter filter;
    private final JvmRuntimeTypes runtimeTypes;
    private final JvmFileClassesProvider fileClassesProvider;

    public CodegenAnnotatingVisitor(@NotNull GenerationState state) {
        this.bindingTrace = state.getBindingTrace();
        this.bindingContext = state.getBindingContext();
        this.filter = state.getGenerateDeclaredClassFilter();
        this.runtimeTypes = state.getJvmRuntimeTypes();
        this.fileClassesProvider = state.getFileClassesProvider();
    }

    @NotNull
    private ClassDescriptor recordClassForCallable(
            @NotNull KtElement element,
            @NotNull CallableDescriptor callableDescriptor,
            @NotNull Collection<KotlinType> supertypes,
            @NotNull String name
    ) {
        String simpleName = name.substring(name.lastIndexOf('/') + 1);
        ClassDescriptor classDescriptor = new SyntheticClassDescriptorForLambda(
                correctContainerForLambda(callableDescriptor, element),
                Name.special("<closure-" + simpleName + ">"),
                supertypes,
                element
        );

        bindingTrace.record(CLASS_FOR_CALLABLE, callableDescriptor, classDescriptor);
        return classDescriptor;
    }

    @NotNull
    @SuppressWarnings("ConstantConditions")
    private DeclarationDescriptor correctContainerForLambda(@NotNull CallableDescriptor descriptor, @NotNull KtElement function) {
        DeclarationDescriptor container = descriptor.getContainingDeclaration();

        // In almost all cases the function's direct container is the correct container to consider in JVM back-end
        // (and subsequently to write to EnclosingMethod and InnerClasses attributes).
        // The only exceptional case is when a lambda is declared in the super call of an anonymous object:
        // in this case it's constructed in the outer code, despite being located under the object PSI- and descriptor-wise
        // TODO: consider the possibility of fixing this in the compiler front-end

        if (container instanceof ConstructorDescriptor && DescriptorUtils.isAnonymousObject(container.getContainingDeclaration())) {
            PsiElement element = function;
            while (element != null) {
                PsiElement child = element;
                element = element.getParent();

                if (bindingContext.get(DECLARATION_TO_DESCRIPTOR, element) == container) return container;

                if (element instanceof KtObjectDeclaration &&
                    element.getParent() instanceof KtObjectLiteralExpression &&
                    child instanceof KtSuperTypeList) {
                    // If we're passing an anonymous object's super call, it means "container" is ConstructorDescriptor of that object.
                    // To reach outer context, we should call getContainingDeclaration() twice
                    // TODO: this is probably not entirely correct, mostly because DECLARATION_TO_DESCRIPTOR can return null
                    container = container.getContainingDeclaration().getContainingDeclaration();
                }
            }
        }

        return container;
    }

    @NotNull
    private String inventAnonymousClassName() {
        String top = peekFromStack(nameStack);
        Integer cnt = anonymousSubclassesCount.get(top);
        if (cnt == null) {
            cnt = 0;
        }
        anonymousSubclassesCount.put(top, cnt + 1);

        return top + "$" + (cnt + 1);
    }

    @Override
    public void visitKtElement(@NotNull KtElement element) {
        super.visitKtElement(element);
        element.acceptChildren(this);
    }

    @Override
    public void visitScript(@NotNull KtScript script) {
        classStack.push(bindingContext.get(SCRIPT, script));
        nameStack.push(AsmUtil.internalNameByFqNameWithoutInnerClasses(script.getFqName()));
        script.acceptChildren(this);
        nameStack.pop();
        classStack.pop();
    }

    @Override
    public void visitKtFile(@NotNull KtFile file) {
        nameStack.push(AsmUtil.internalNameByFqNameWithoutInnerClasses(file.getPackageFqName()));
        file.acceptChildren(this);
        nameStack.pop();
    }

    @Override
    public void visitEnumEntry(@NotNull KtEnumEntry enumEntry) {
        if (enumEntry.getDeclarations().isEmpty()) {
            for (KtSuperTypeListEntry specifier : enumEntry.getSuperTypeListEntries()) {
                specifier.accept(this);
            }
            return;
        }

        ClassDescriptor descriptor = bindingContext.get(CLASS, enumEntry);
        // working around a problem with shallow analysis
        if (descriptor == null) return;

        bindingTrace.record(ENUM_ENTRY_CLASS_NEED_SUBCLASS, descriptor);
        super.visitEnumEntry(enumEntry);
    }

    @Override
    public void visitObjectDeclaration(@NotNull KtObjectDeclaration declaration) {
        if (!filter.shouldAnnotateClass(declaration)) return;

        ClassDescriptor classDescriptor = bindingContext.get(CLASS, declaration);
        // working around a problem with shallow analysis
        if (classDescriptor == null) return;

        String name = getName(classDescriptor);
        recordClosure(classDescriptor, name);

        classStack.push(classDescriptor);
        nameStack.push(name);
        super.visitObjectDeclaration(declaration);
        nameStack.pop();
        classStack.pop();
    }

    @Override
    public void visitClass(@NotNull KtClass klass) {
        if (!filter.shouldAnnotateClass(klass)) return;

        ClassDescriptor classDescriptor = bindingContext.get(CLASS, klass);
        // working around a problem with shallow analysis
        if (classDescriptor == null) return;

        String name = getName(classDescriptor);
        recordClosure(classDescriptor, name);

        classStack.push(classDescriptor);
        nameStack.push(name);
        super.visitClass(klass);
        nameStack.pop();
        classStack.pop();
    }

    private String getName(ClassDescriptor classDescriptor) {
        String base = peekFromStack(nameStack);
        Name descriptorName = safeIdentifier(classDescriptor.getName());
        return DescriptorUtils.isTopLevelDeclaration(classDescriptor) ? base.isEmpty() ? descriptorName
                        .asString() : base + '/' + descriptorName : base + '$' + descriptorName;
    }

    @Override
    public void visitObjectLiteralExpression(@NotNull KtObjectLiteralExpression expression) {
        KtObjectDeclaration object = expression.getObjectDeclaration();
        ClassDescriptor classDescriptor = bindingContext.get(CLASS, object);
        if (classDescriptor == null) {
            // working around a problem with shallow analysis
            super.visitObjectLiteralExpression(expression);
            return;
        }

        String name = inventAnonymousClassName();
        recordClosure(classDescriptor, name);

        KtSuperTypeList delegationSpecifierList = object.getSuperTypeList();
        if (delegationSpecifierList != null) {
            delegationSpecifierList.accept(this);
        }

        classStack.push(classDescriptor);
        nameStack.push(CodegenBinding.getAsmType(bindingContext, classDescriptor).getInternalName());
        KtClassBody body = object.getBody();
        if (body != null) {
            super.visitClassBody(body);
        }
        nameStack.pop();
        classStack.pop();
    }

    @Override
    public void visitLambdaExpression(@NotNull KtLambdaExpression lambdaExpression) {
        KtFunctionLiteral functionLiteral = lambdaExpression.getFunctionLiteral();
        FunctionDescriptor functionDescriptor =
                (FunctionDescriptor) bindingContext.get(DECLARATION_TO_DESCRIPTOR, functionLiteral);
        // working around a problem with shallow analysis
        if (functionDescriptor == null) return;

        String name = inventAnonymousClassName();
        Collection<KotlinType> supertypes = runtimeTypes.getSupertypesForClosure(functionDescriptor);
        ClassDescriptor classDescriptor = recordClassForCallable(functionLiteral, functionDescriptor, supertypes, name);
        recordClosure(classDescriptor, name);

        classStack.push(classDescriptor);
        nameStack.push(name);
        super.visitLambdaExpression(lambdaExpression);
        nameStack.pop();
        classStack.pop();
    }

    @Override
    public void visitCallableReferenceExpression(@NotNull KtCallableReferenceExpression expression) {
        ResolvedCall<?> referencedFunction = CallUtilKt.getResolvedCall(expression.getCallableReference(), bindingContext);
        if (referencedFunction == null) return;
        CallableDescriptor target = referencedFunction.getResultingDescriptor();

        CallableDescriptor callableDescriptor;
        Collection<KotlinType> supertypes;

        if (target instanceof FunctionDescriptor) {
            callableDescriptor = bindingContext.get(FUNCTION, expression);
            if (callableDescriptor == null) return;

            supertypes = runtimeTypes.getSupertypesForFunctionReference((FunctionDescriptor) target);
        }
        else if (target instanceof PropertyDescriptor) {
            callableDescriptor = bindingContext.get(VARIABLE, expression);
            if (callableDescriptor == null) return;

            supertypes = Collections.singleton(runtimeTypes.getSupertypeForPropertyReference((PropertyDescriptor) target));
        }
        else {
            return;
        }

        String name = inventAnonymousClassName();
        ClassDescriptor classDescriptor = recordClassForCallable(expression, callableDescriptor, supertypes, name);
        recordClosure(classDescriptor, name);

        classStack.push(classDescriptor);
        nameStack.push(name);
        super.visitCallableReferenceExpression(expression);
        nameStack.pop();
        classStack.pop();
    }

    private void recordClosure(@NotNull ClassDescriptor classDescriptor, @NotNull String name) {
        CodegenBinding.recordClosure(bindingTrace, classDescriptor, peekFromStack(classStack), Type.getObjectType(name),
                                     fileClassesProvider);
    }

    @Override
    public void visitProperty(@NotNull KtProperty property) {
        DeclarationDescriptor descriptor = bindingContext.get(DECLARATION_TO_DESCRIPTOR, property);
        // working around a problem with shallow analysis
        if (descriptor == null) return;

        String nameForClassOrPackageMember = getNameForClassOrPackageMember(descriptor);
        if (nameForClassOrPackageMember != null) {
            nameStack.push(nameForClassOrPackageMember);
        }
        else {
            nameStack.push(peekFromStack(nameStack) + '$' + safeIdentifier(property.getNameAsSafeName()).asString());
        }

        KtPropertyDelegate delegate = property.getDelegate();
        if (delegate != null && descriptor instanceof PropertyDescriptor) {
            PropertyDescriptor propertyDescriptor = (PropertyDescriptor) descriptor;
            String name = inventAnonymousClassName();
            KotlinType supertype = runtimeTypes.getSupertypeForPropertyReference(propertyDescriptor);
            ClassDescriptor classDescriptor = recordClassForCallable(delegate, propertyDescriptor, Collections.singleton(supertype), name);
            recordClosure(classDescriptor, name);
        }

        super.visitProperty(property);
        nameStack.pop();
    }

    @Override
    public void visitNamedFunction(@NotNull KtNamedFunction function) {
        FunctionDescriptor functionDescriptor = (FunctionDescriptor) bindingContext.get(DECLARATION_TO_DESCRIPTOR, function);
        // working around a problem with shallow analysis
        if (functionDescriptor == null) return;

        String nameForClassOrPackageMember = getNameForClassOrPackageMember(functionDescriptor);
        if (nameForClassOrPackageMember != null) {
            nameStack.push(nameForClassOrPackageMember);
            super.visitNamedFunction(function);
            nameStack.pop();
        }
        else {
            String name = inventAnonymousClassName();
            Collection<KotlinType> supertypes = runtimeTypes.getSupertypesForClosure(functionDescriptor);
            ClassDescriptor classDescriptor = recordClassForCallable(function, functionDescriptor, supertypes, name);
            recordClosure(classDescriptor, name);

            classStack.push(classDescriptor);
            nameStack.push(name);
            super.visitNamedFunction(function);
            nameStack.pop();
            classStack.pop();
        }
    }

    @Nullable
    private String getNameForClassOrPackageMember(@NotNull DeclarationDescriptor descriptor) {
        DeclarationDescriptor containingDeclaration = descriptor.getContainingDeclaration();

        String peek = peekFromStack(nameStack);
        String name = safeIdentifier(descriptor.getName()).asString();
        if (containingDeclaration instanceof ClassDescriptor) {
            return peek + '$' + name;
        }
        else if (containingDeclaration instanceof PackageFragmentDescriptor) {
            KtFile containingFile = DescriptorToSourceUtils.getContainingFile(descriptor);
            assert containingFile != null : "File not found for " + descriptor;
            return FileClasses.getFileClassInternalName(fileClassesProvider, containingFile) + '$' + name;
        }

        return null;
    }

    @Override
    public void visitCallExpression(@NotNull KtCallExpression expression) {
        super.visitCallExpression(expression);
        checkSamCall(expression);
    }

    private void checkSamCall(@NotNull KtCallElement expression) {
        ResolvedCall<?> call = CallUtilKt.getResolvedCall(expression, bindingContext);
        if (call == null) return;

        CallableDescriptor descriptor = call.getResultingDescriptor();
        if (!(descriptor instanceof FunctionDescriptor)) return;

        recordSamConstructorIfNeeded(expression, call);

        FunctionDescriptor original = SamCodegenUtil.getOriginalIfSamAdapter((FunctionDescriptor) descriptor);
        if (original == null) return;

        List<ResolvedValueArgument> valueArguments = call.getValueArgumentsByIndex();
        if (valueArguments == null) return;

        for (ValueParameterDescriptor valueParameter : original.getValueParameters()) {
            SamType samType = SamType.create(valueParameter.getType());
            if (samType == null) continue;

            ResolvedValueArgument resolvedValueArgument = valueArguments.get(valueParameter.getIndex());
            assert resolvedValueArgument instanceof ExpressionValueArgument : resolvedValueArgument;
            ValueArgument valueArgument = ((ExpressionValueArgument) resolvedValueArgument).getValueArgument();
            assert valueArgument != null;
            KtExpression argumentExpression = valueArgument.getArgumentExpression();
            assert argumentExpression != null : valueArgument.asElement().getText();

            bindingTrace.record(CodegenBinding.SAM_VALUE, argumentExpression, samType);
        }
    }

    @Override
    public void visitSuperTypeCallEntry(@NotNull KtSuperTypeCallEntry call) {
        super.visitSuperTypeCallEntry(call);
        checkSamCall(call);
    }

    private void recordSamConstructorIfNeeded(@NotNull KtCallElement expression, @NotNull ResolvedCall<?> call) {
        CallableDescriptor callableDescriptor = call.getResultingDescriptor();
        if (!(callableDescriptor.getOriginal() instanceof SamConstructorDescriptor)) return;

        List<ResolvedValueArgument> valueArguments = call.getValueArgumentsByIndex();
        if (valueArguments == null || valueArguments.size() != 1) return;

        ResolvedValueArgument valueArgument = valueArguments.get(0);
        if (!(valueArgument instanceof ExpressionValueArgument)) return;
        ValueArgument argument = ((ExpressionValueArgument) valueArgument).getValueArgument();
        if (argument == null) return;

        KtExpression argumentExpression = argument.getArgumentExpression();
        bindingTrace.record(SAM_CONSTRUCTOR_TO_ARGUMENT, expression, argumentExpression);

        //noinspection ConstantConditions
        SamType samType = SamType.create(callableDescriptor.getReturnType());
        bindingTrace.record(SAM_VALUE, argumentExpression, samType);
    }

    @Override
    public void visitBinaryExpression(@NotNull KtBinaryExpression expression) {
        super.visitBinaryExpression(expression);

        DeclarationDescriptor operationDescriptor = bindingContext.get(BindingContext.REFERENCE_TARGET, expression.getOperationReference());
        if (!(operationDescriptor instanceof FunctionDescriptor)) return;

        FunctionDescriptor original = SamCodegenUtil.getOriginalIfSamAdapter((FunctionDescriptor) operationDescriptor);
        if (original == null) return;

        SamType samType = SamType.create(original.getValueParameters().get(0).getType());
        if (samType == null) return;

        IElementType token = expression.getOperationToken();
        if (BINARY_OPERATIONS.contains(token)) {
            bindingTrace.record(CodegenBinding.SAM_VALUE, expression.getRight(), samType);
        }
        else if (token == IN_KEYWORD || token == NOT_IN) {
            bindingTrace.record(CodegenBinding.SAM_VALUE, expression.getLeft(), samType);
        }
    }

    @Override
    public void visitArrayAccessExpression(@NotNull KtArrayAccessExpression expression) {
        super.visitArrayAccessExpression(expression);

        DeclarationDescriptor operationDescriptor = bindingContext.get(BindingContext.REFERENCE_TARGET, expression);
        if (!(operationDescriptor instanceof FunctionDescriptor)) return;

        boolean isSetter = operationDescriptor.getName().asString().equals("set");
        FunctionDescriptor original = SamCodegenUtil.getOriginalIfSamAdapter((FunctionDescriptor) operationDescriptor);
        if (original == null) return;

        List<KtExpression> indexExpressions = expression.getIndexExpressions();
        List<ValueParameterDescriptor> parameters = original.getValueParameters();
        for (ValueParameterDescriptor valueParameter : parameters) {
            SamType samType = SamType.create(valueParameter.getType());
            if (samType == null) continue;

            if (isSetter && valueParameter.getIndex() == parameters.size() - 1) {
                PsiElement parent = expression.getParent();
                if (parent instanceof KtBinaryExpression && ((KtBinaryExpression) parent).getOperationToken() == EQ) {
                    KtExpression right = ((KtBinaryExpression) parent).getRight();
                    bindingTrace.record(CodegenBinding.SAM_VALUE, right, samType);
                }
            }
            else {
                KtExpression indexExpression = indexExpressions.get(valueParameter.getIndex());
                bindingTrace.record(CodegenBinding.SAM_VALUE, indexExpression, samType);
            }
        }
    }

    @Override
    public void visitWhenExpression(@NotNull KtWhenExpression expression) {
        super.visitWhenExpression(expression);
        if (!isWhenWithEnums(expression)) return;

        String currentClassName = getCurrentTopLevelClassOrPackagePartInternalName(expression.getContainingKtFile());

        if (bindingContext.get(MAPPINGS_FOR_WHENS_BY_ENUM_IN_CLASS_FILE, currentClassName) == null) {
            bindingTrace.record(MAPPINGS_FOR_WHENS_BY_ENUM_IN_CLASS_FILE, currentClassName, new ArrayList<WhenByEnumsMapping>(1));
        }

        List<WhenByEnumsMapping> mappings = bindingContext.get(MAPPINGS_FOR_WHENS_BY_ENUM_IN_CLASS_FILE, currentClassName);
        assert mappings != null : "guaranteed by contract";

        int fieldNumber = mappings.size();

        assert expression.getSubjectExpression() != null : "subject expression should be not null in a valid when by enums";
        KotlinType type = bindingContext.getType(expression.getSubjectExpression());
        assert type != null : "should not be null in a valid when by enums";
        ClassDescriptor classDescriptor = (ClassDescriptor) type.getConstructor().getDeclarationDescriptor();
        assert classDescriptor != null : "because it's enum";

        WhenByEnumsMapping mapping = new WhenByEnumsMapping(classDescriptor, currentClassName, fieldNumber);

        for (ConstantValue<?> constant : SwitchCodegenUtil.getAllConstants(expression, bindingContext)) {
            if (constant instanceof NullValue) continue;

            assert constant instanceof EnumValue : "expression in when should be EnumValue";
            mapping.putFirstTime((EnumValue) constant, mapping.size() + 1);
        }

        mappings.add(mapping);

        bindingTrace.record(MAPPING_FOR_WHEN_BY_ENUM, expression, mapping);
    }

    private boolean isWhenWithEnums(@NotNull KtWhenExpression expression) {
        return WhenChecker.isWhenByEnum(expression, bindingContext) &&
               SwitchCodegenUtil.checkAllItemsAreConstantsSatisfying(
                       expression,
                       bindingContext,
                       new Function1<ConstantValue<?>, Boolean>() {
                           @Override
                           public Boolean invoke(@NotNull ConstantValue<?> constant) {
                               return constant instanceof EnumValue || constant instanceof NullValue;
                           }
                       }
               );
    }

    @NotNull
    private String getCurrentTopLevelClassOrPackagePartInternalName(@NotNull KtFile file) {
        ListIterator<ClassDescriptor> iterator = classStack.listIterator(classStack.size());
        while (iterator.hasPrevious()) {
            ClassDescriptor previous = iterator.previous();
            if (DescriptorUtils.isTopLevelOrInnerClass(previous)) {
                return CodegenBinding.getAsmType(bindingContext, previous).getInternalName();
            }
        }

        return FileClasses.getFacadeClassInternalName(fileClassesProvider, file);
    }

    private static <T> T peekFromStack(@NotNull Stack<T> stack) {
        return stack.empty() ? null : stack.peek();
    }
}
