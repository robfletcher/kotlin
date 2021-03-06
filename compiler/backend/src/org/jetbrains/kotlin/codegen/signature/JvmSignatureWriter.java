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

package org.jetbrains.kotlin.codegen.signature;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.name.Name;
import org.jetbrains.kotlin.resolve.jvm.jvmSignature.JvmMethodGenericSignature;
import org.jetbrains.kotlin.resolve.jvm.jvmSignature.JvmMethodParameterKind;
import org.jetbrains.kotlin.resolve.jvm.jvmSignature.JvmMethodParameterSignature;
import org.jetbrains.kotlin.resolve.jvm.jvmSignature.JvmMethodSignature;
import org.jetbrains.kotlin.types.Variance;
import org.jetbrains.org.objectweb.asm.Type;
import org.jetbrains.org.objectweb.asm.commons.Method;

import java.util.ArrayList;
import java.util.List;

public class JvmSignatureWriter {

    private final List<JvmMethodParameterSignature> kotlinParameterTypes = new ArrayList<JvmMethodParameterSignature>();

    private int jvmCurrentTypeArrayLevel;
    private Type jvmCurrentType;
    private Type jvmReturnType;

    private JvmMethodParameterKind currentParameterKind;

    private int currentSignatureSize = 0;

    /**
     * Shortcut
     */
    public void writeAsmType(Type asmType) {
        switch (asmType.getSort()) {
            case Type.OBJECT:
                writeClassBegin(asmType);
                writeClassEnd();
                return;
            case Type.ARRAY:
                writeArrayType();
                writeAsmType(asmType.getElementType());
                writeArrayEnd();
                return;
            default:
                writeAsmType0(asmType);
        }
    }

    private String makeArrayPrefix() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < jvmCurrentTypeArrayLevel; ++i) {
            sb.append('[');
        }
        return sb.toString();
    }

    protected void writeAsmType0(Type type) {
        if (jvmCurrentType == null) {
            jvmCurrentType = Type.getType(makeArrayPrefix() + type.getDescriptor());
        }
    }

    public void writeClassBegin(Type asmType) {
        writeAsmType0(asmType);
    }

    public void writeOuterClassBegin(Type resultingAsmType, String outerInternalName) {
        writeAsmType0(resultingAsmType);
    }

    public void writeInnerClass(String name) {
    }

    public void writeClassEnd() {
    }

    public void writeArrayType() {
        if (jvmCurrentType == null) {
            ++jvmCurrentTypeArrayLevel;
        }
    }

    public void writeArrayEnd() {
    }

    public void writeTypeArgument(@NotNull Variance projectionKind) {
    }

    public void writeUnboundedWildcard() {
    }

    public void writeTypeArgumentEnd() {
    }

    public void writeTypeVariable(Name name, Type asmType) {
        writeAsmType0(asmType);
    }

    public void writeFormalTypeParameter(String name) {
    }

    public void writeClassBound() {
    }

    public void writeClassBoundEnd() {
    }

    public void writeInterfaceBound() {
    }

    public void writeInterfaceBoundEnd() {
    }

    public void writeParametersStart() {
        // hacks
        jvmCurrentType = null;
        jvmCurrentTypeArrayLevel = 0;
    }

    public void writeParameterType(JvmMethodParameterKind parameterKind) {
        currentParameterKind = parameterKind;
    }

    public void writeParameterTypeEnd() {
        kotlinParameterTypes.add(new JvmMethodParameterSignature(jvmCurrentType, currentParameterKind));
        currentSignatureSize += jvmCurrentType.getSize();

        currentParameterKind = null;
        jvmCurrentType = null;
        jvmCurrentTypeArrayLevel = 0;
    }

    public void writeReturnType() {
    }

    public void writeReturnTypeEnd() {
        jvmReturnType = jvmCurrentType;
        jvmCurrentType = null;
        jvmCurrentTypeArrayLevel = 0;
    }

    public void writeSuperclass() {
    }

    public void writeSuperclassEnd() {
    }

    public void writeInterface() {
    }

    public void writeInterfaceEnd() {
    }

    @Nullable
    public String makeJavaGenericSignature() {
        return null;
    }

    @NotNull
    public JvmMethodGenericSignature makeJvmMethodSignature(@NotNull String name) {
        List<Type> types = new ArrayList<Type>(kotlinParameterTypes.size());
        for (JvmMethodParameterSignature parameter : kotlinParameterTypes) {
            types.add(parameter.getAsmType());
        }
        Method asmMethod = new Method(name, jvmReturnType, types.toArray(new Type[types.size()]));
        return new JvmMethodGenericSignature(asmMethod, kotlinParameterTypes, makeJavaGenericSignature());
    }

    public int getCurrentSignatureSize() {
        return currentSignatureSize;
    }

    public boolean skipGenericSignature() {
        return true;
    }

    @Override
    public String toString() {
        return "empty";
    }
}

