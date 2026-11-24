/*
 *  Copyright 2017 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.model.transformation;

import org.teavm.model.BasicBlock;
import org.teavm.model.ClassReader;
import org.teavm.model.ClassReaderSource;
import org.teavm.model.ElementModifier;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodReader;
import org.teavm.model.Program;
import org.teavm.model.analysis.ClassInitializerInfo;
import org.teavm.model.instructions.InitClassInstruction;

public class ClassInitializerInsertionTransformer {
    private static final MethodDescriptor clinitDescriptor = new MethodDescriptor("<clinit>", void.class);
    private ClassReaderSource classes;
    private ClassInitializerInfo classInitializerInfo;

    public ClassInitializerInsertionTransformer(ClassReaderSource classes, ClassInitializerInfo classInitializerInfo) {
        this.classes = classes;
        this.classInitializerInfo = classInitializerInfo;
    }

    public void apply(MethodReader method, Program program) {
        if (program.basicBlockCount() == 0) {
            return;
        }
        ClassReader cls = classes.get(method.getOwnerName());
        if (cls == null) {
            return;
        }

        // TeaVM inserts a class's own initializer at the start of every constructor. Since the
        // subclass constructor runs before the superclass constructor, that initializes the
        // subclass before its superclass, which violates the JVM's class initialization order.
        // Minecraft relies on that order: SynchedEntityData.defineId assigns ids from a registry
        // shared by the whole hierarchy, so initializing LivingEntity before Entity makes both
        // classes claim id 0 and the first spawned player dies with "Duplicate id value for 0!".
        // Initialize the superclass first, from inside <clinit>, to restore the required order.
        if (method.getName().equals("<clinit>") && cls.getParent() != null) {
            String parentName = cls.getParent();
            ClassReader parent = classes.get(parentName);
            if (parent != null && parent.getMethod(clinitDescriptor) != null
                    && classInitializerInfo.isDynamicInitializer(parentName)) {
                BasicBlock entryBlock = program.basicBlockAt(0);
                InitClassInstruction initInsn = new InitClassInstruction();
                initInsn.setClassName(parentName);
                entryBlock.addFirst(initInsn);
            }
        }

        boolean hasClinit = cls.getMethod(clinitDescriptor) != null
                && classInitializerInfo.isDynamicInitializer(cls.getName());
        if (hasClinit && needsClinitCall(method)) {
            BasicBlock entryBlock = program.basicBlockAt(0);
            InitClassInstruction initInsn = new InitClassInstruction();
            initInsn.setClassName(method.getOwnerName());
            entryBlock.addFirst(initInsn);
        }
    }

    private static boolean needsClinitCall(MethodReader method) {
        if (method.getName().equals("<clinit>")) {
            return false;
        }
        if (method.getName().equals("<init>")) {
            return true;
        }
        return method.hasModifier(ElementModifier.STATIC);
    }
}
