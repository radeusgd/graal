/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.graalvm.wasm;

import static org.graalvm.wasm.Assert.assertByteEqual;
import static org.graalvm.wasm.Assert.assertIntEqual;
import static org.graalvm.wasm.Assert.assertIntLessOrEqual;
import static org.graalvm.wasm.Assert.assertTrue;
import static org.graalvm.wasm.Assert.assertUnsignedIntLess;
import static org.graalvm.wasm.Assert.assertUnsignedIntLessOrEqual;
import static org.graalvm.wasm.Assert.assertUnsignedLongLessOrEqual;
import static org.graalvm.wasm.Assert.fail;
import static org.graalvm.wasm.WasmType.EXTERNREF_TYPE;
import static org.graalvm.wasm.WasmType.F32_TYPE;
import static org.graalvm.wasm.WasmType.F64_TYPE;
import static org.graalvm.wasm.WasmType.FUNCREF_TYPE;
import static org.graalvm.wasm.WasmType.I32_TYPE;
import static org.graalvm.wasm.WasmType.I64_TYPE;
import static org.graalvm.wasm.WasmType.NULL_TYPE;
import static org.graalvm.wasm.WasmType.VOID_TYPE;
import static org.graalvm.wasm.constants.Sizes.MAX_MEMORY_64_DECLARATION_SIZE;
import static org.graalvm.wasm.constants.Sizes.MAX_MEMORY_DECLARATION_SIZE;
import static org.graalvm.wasm.constants.Sizes.MAX_TABLE_DECLARATION_SIZE;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import org.graalvm.wasm.collection.ByteArrayList;
import org.graalvm.wasm.constants.Bytecode;
import org.graalvm.wasm.constants.ExportIdentifier;
import org.graalvm.wasm.constants.GlobalModifier;
import org.graalvm.wasm.constants.ImportIdentifier;
import org.graalvm.wasm.constants.Instructions;
import org.graalvm.wasm.constants.LimitsPrefix;
import org.graalvm.wasm.constants.Section;
import org.graalvm.wasm.constants.SegmentMode;
import org.graalvm.wasm.exception.Failure;
import org.graalvm.wasm.exception.WasmException;
import org.graalvm.wasm.parser.bytecode.BytecodeGen;
import org.graalvm.wasm.parser.ir.CallNode;
import org.graalvm.wasm.parser.ir.CodeEntry;
import org.graalvm.wasm.parser.validation.ParserState;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.interop.ExceptionType;

/**
 * Simple recursive-descend parser for the binary WebAssembly format.
 */
public class BinaryParser extends BinaryStreamParser {

    private static final int MAGIC = 0x6d736100;
    private static final int VERSION = 0x00000001;

    private final WasmModule module;
    private final WasmContext wasmContext;
    private final int[] multiResult;
    private final long[] longMultiResult;

    private final boolean multiValue;

    private final boolean bulkMemoryAndRefTypes;
    private final boolean memory64;

    private final boolean unsafeMemory;

    @CompilerDirectives.TruffleBoundary
    public BinaryParser(WasmModule module, WasmContext context) {
        super(module.data());
        this.module = module;
        this.wasmContext = context;
        this.multiResult = new int[2];
        this.longMultiResult = new long[2];
        this.multiValue = context.getContextOptions().supportMultiValue();
        this.bulkMemoryAndRefTypes = context.getContextOptions().supportBulkMemoryAndRefTypes();
        this.memory64 = context.getContextOptions().supportMemory64();
        this.unsafeMemory = context.getContextOptions().useUnsafeMemory();
    }

    @CompilerDirectives.TruffleBoundary
    public void readModule() {
        module.limits().checkModuleSize(data.length);
        validateMagicNumberAndVersion();
        readSymbolSections();
    }

    private void validateMagicNumberAndVersion() {
        assertIntEqual(read4(), MAGIC, Failure.INVALID_MAGIC_NUMBER);
        assertIntEqual(read4(), VERSION, Failure.INVALID_VERSION_NUMBER);
    }

    private void readSymbolSections() {
        int lastNonCustomSection = 0;
        boolean hasCodeSection = false;
        BytecodeGen bytecode = new BytecodeGen();
        while (!isEOF()) {
            final byte sectionID = read1();

            if (sectionID != Section.CUSTOM) {
                if (Section.isNextSectionOrderValid(sectionID, lastNonCustomSection)) {
                    if (Integer.compareUnsigned(sectionID, Section.LAST_SECTION_ID) > 0) {
                        fail(Failure.MALFORMED_SECTION_ID, "invalid section ID: " + sectionID);
                    }
                    lastNonCustomSection = sectionID;
                } else if (Integer.compareUnsigned(sectionID, Section.LAST_SECTION_ID) > 0 || lastNonCustomSection == sectionID) {
                    throw WasmException.create(Failure.UNEXPECTED_CONTENT_AFTER_LAST_SECTION);
                } else {
                    throw WasmException.create(Failure.INVALID_SECTION_ORDER, "Section " + sectionID + " defined after section " + lastNonCustomSection);
                }
            }

            final int size = readLength();
            final int startOffset = offset;
            switch (sectionID) {
                case Section.CUSTOM:
                    readCustomSection(size);
                    break;
                case Section.TYPE:
                    readTypeSection();
                    break;
                case Section.IMPORT:
                    readImportSection();
                    break;
                case Section.FUNCTION:
                    readFunctionSection();
                    break;
                case Section.TABLE:
                    readTableSection();
                    break;
                case Section.MEMORY:
                    readMemorySection();
                    break;
                case Section.GLOBAL:
                    readGlobalSection();
                    break;
                case Section.EXPORT:
                    readExportSection();
                    break;
                case Section.START:
                    readStartSection();
                    break;
                case Section.ELEMENT:
                    readElementSection(bytecode);
                    break;
                case Section.DATA_COUNT:
                    if (bulkMemoryAndRefTypes) {
                        readDataCountSection(size);
                    } else {
                        fail(Failure.MALFORMED_SECTION_ID, "invalid section ID: " + sectionID);
                    }
                    break;
                case Section.CODE:
                    readCodeSection(bytecode);
                    hasCodeSection = true;
                    break;
                case Section.DATA:
                    readDataSection(bytecode);
                    break;
            }
            assertIntEqual(offset - startOffset, size, String.format("Declared section (0x%02X) size is incorrect", sectionID), Failure.SECTION_SIZE_MISMATCH);
        }
        if (!hasCodeSection) {
            assertIntEqual(module.numFunctions(), module.importedFunctions().size(), Failure.FUNCTIONS_CODE_INCONSISTENT_LENGTHS);
        }
        module.setBytecode(bytecode.toArray());
        module.removeFunctionReferences();
    }

    private void readCustomSection(int size) {
        final int sectionEndOffset = offset + size;
        final String name = readName();
        Assert.assertUnsignedIntLessOrEqual(sectionEndOffset, data.length, Failure.LENGTH_OUT_OF_BOUNDS);
        Assert.assertUnsignedIntLessOrEqual(offset, sectionEndOffset, Failure.UNEXPECTED_END);
        final byte[] sectionData = new byte[sectionEndOffset - offset];
        System.arraycopy(data, offset, sectionData, 0, sectionEndOffset - offset);
        module.allocateCustomSection(name, sectionData);
        if ("name".equals(name)) {
            try {
                readNameSection();
            } catch (WasmException ex) {
                // Malformed name section should not result in invalidation of the module
                assert ex.getExceptionType() == ExceptionType.PARSE_ERROR;
            }
        }
        offset = sectionEndOffset;
    }

    /**
     * @see <a href=
     *      "https://webassembly.github.io/spec/core/appendix/custom.html#binary-namesubsection"><code>namedata</code>
     *      binary specification</a>
     */
    private void readNameSection() {
        if (!isEOF() && peek1() == 0) {
            readModuleName();
        }
        if (!isEOF() && peek1() == 1) {
            readFunctionNames();
        }
        if (!isEOF() && peek1() == 2) {
            readLocalNames();
        }
    }

    /**
     * @see <a href=
     *      "https://webassembly.github.io/spec/core/appendix/custom.html#binary-modulenamesec"><code>modulenamesubsec</code>
     *      binary specification</a>
     */
    private void readModuleName() {
        final int subsectionId = read1();
        assert subsectionId == 0;
        final int size = readLength();
        // We don't currently use debug module name.
        offset += size;
    }

    /**
     * @see <a href=
     *      "https://webassembly.github.io/spec/core/appendix/custom.html#binary-funcnamesec"><code>funcnamesubsec</code>
     *      binary specification</a>
     */
    private void readFunctionNames() {
        final int subsectionId = read1();
        assert subsectionId == 1;
        final int size = readLength();
        final int startOffset = offset;
        final int length = readLength();
        final int maxFunctionIndex = module.numFunctions() - 1;
        for (int i = 0; i < length; ++i) {
            final int functionIndex = readFunctionIndex();
            assertIntLessOrEqual(0, functionIndex, "Negative function index", Failure.UNSPECIFIED_MALFORMED);
            assertIntLessOrEqual(functionIndex, maxFunctionIndex, "Function index too large", Failure.UNSPECIFIED_MALFORMED);
            final String functionName = readName();
            module.function(functionIndex).setDebugName(functionName);
        }
        assertIntEqual(offset - startOffset, size, Failure.SECTION_SIZE_MISMATCH);
    }

    /**
     * @see <a href=
     *      "https://webassembly.github.io/spec/core/appendix/custom.html#local-names"><code>localnamesubsec</code>
     *      binary specification</a>
     */
    private void readLocalNames() {
        final int subsectionId = read1();
        assert subsectionId == 2;
        final int size = readLength();
        // We don't currently use debug local names.
        offset += size;
    }

    private void readTypeSection() {
        final int typeCount = readLength();
        module.limits().checkTypeCount(typeCount);
        for (int typeIndex = 0; typeIndex != typeCount; typeIndex++) {
            assertTrue(!isEOF(), Failure.LENGTH_OUT_OF_BOUNDS);
            final byte type = read1();
            if (type == 0x60) {
                readFunctionType();
            } else {
                // According to the official tests this should be an integer presentation too long
                // error
                fail(Failure.INTEGER_REPRESENTATION_TOO_LONG, "Only function types are supported in the type section");
            }
        }
    }

    private void readImportSection() {
        assertIntEqual(module.symbolTable().numGlobals(), 0,
                        "The global index should be -1 when the import section is first read.", Failure.UNSPECIFIED_INVALID);
        int importCount = readLength();

        module.limits().checkImportCount(importCount);
        for (int importIndex = 0; importIndex != importCount; importIndex++) {
            assertTrue(!isEOF(), Failure.LENGTH_OUT_OF_BOUNDS);
            String moduleName = readName();
            String memberName = readName();
            byte importType = readImportType();
            switch (importType) {
                case ImportIdentifier.FUNCTION: {
                    int typeIndex = readTypeIndex();
                    module.symbolTable().importFunction(moduleName, memberName, typeIndex);
                    break;
                }
                case ImportIdentifier.TABLE: {
                    final byte elemType = readRefType();
                    if (!bulkMemoryAndRefTypes) {
                        assertByteEqual(elemType, FUNCREF_TYPE, "Invalid element type for table import", Failure.UNSPECIFIED_MALFORMED);
                    }
                    readTableLimits(multiResult);
                    final int tableIndex = module.tableCount();
                    module.symbolTable().importTable(moduleName, memberName, tableIndex, multiResult[0], multiResult[1], elemType, bulkMemoryAndRefTypes);
                    break;
                }
                case ImportIdentifier.MEMORY: {
                    final boolean is64Bit = readMemoryLimits(longMultiResult);
                    module.symbolTable().importMemory(moduleName, memberName, longMultiResult[0], longMultiResult[1], is64Bit);
                    break;
                }
                case ImportIdentifier.GLOBAL: {
                    byte type = readValueType(bulkMemoryAndRefTypes);
                    byte mutability = readMutability();
                    int index = module.symbolTable().numGlobals();
                    module.symbolTable().importGlobal(moduleName, memberName, index, type, mutability);
                    break;
                }
                default: {
                    fail(Failure.MALFORMED_IMPORT_KIND, String.format("Invalid import type identifier: 0x%02X", importType));
                }
            }
        }
    }

    private void readFunctionSection() {
        int functionCount = readLength();
        module.limits().checkFunctionCount(functionCount);
        for (int functionIndex = 0; functionIndex != functionCount; functionIndex++) {
            assertTrue(!isEOF(), Failure.LENGTH_OUT_OF_BOUNDS);
            int functionTypeIndex = readUnsignedInt32();
            module.symbolTable().declareFunction(functionTypeIndex);
        }
    }

    private void readTableSection() {
        final int tableCount = readLength();
        final int startingTableIndex = module.tableCount();
        module.limits().checkTableCount(startingTableIndex + tableCount);
        for (int tableIndex = startingTableIndex; tableIndex != startingTableIndex + tableCount; tableIndex++) {
            assertTrue(!isEOF(), Failure.LENGTH_OUT_OF_BOUNDS);
            final byte elemType = readRefType();
            readTableLimits(multiResult);
            module.symbolTable().allocateTable(tableIndex, multiResult[0], multiResult[1], elemType, bulkMemoryAndRefTypes);
        }
    }

    private void readMemorySection() {
        final int memoryCount = readLength();
        // Since in the current version of WebAssembly supports at most one table instance per
        // module, this loop should be executed at most once. `SymbolTable#allocateMemory` fails if
        // it is not the case.
        for (int memoryIndex = 0; memoryIndex != memoryCount; memoryIndex++) {
            assertTrue(!isEOF(), Failure.LENGTH_OUT_OF_BOUNDS);
            final boolean is64Bit = readMemoryLimits(longMultiResult);
            module.symbolTable().allocateMemory(longMultiResult[0], longMultiResult[1], is64Bit);
        }
    }

    private void readCodeSection(BytecodeGen bytecode) {
        final int importedFunctionCount = module.importedFunctions().size();
        final int codeEntryCount = readLength();
        final int expectedCodeEntryCount = module.numFunctions() - importedFunctionCount;
        assertIntEqual(codeEntryCount, expectedCodeEntryCount, Failure.FUNCTIONS_CODE_INCONSISTENT_LENGTHS);
        final CodeEntry[] codeEntries = new CodeEntry[codeEntryCount];
        for (int entryIndex = 0; entryIndex != codeEntryCount; entryIndex++) {
            assertTrue(!isEOF(), Failure.LENGTH_OUT_OF_BOUNDS);
            final int codeEntrySize = readUnsignedInt32();
            final int startOffset = offset;
            module.limits().checkFunctionSize(codeEntrySize);
            final ByteArrayList locals = readCodeEntryLocals();
            final int localCount = locals.size() + module.function(importedFunctionCount + entryIndex).paramCount();
            module.limits().checkLocalCount(localCount);
            codeEntries[entryIndex] = readCodeEntry(importedFunctionCount + entryIndex, locals, startOffset + codeEntrySize, entryIndex < codeEntryCount - 1, bytecode, entryIndex);
            assertIntEqual(offset - startOffset, codeEntrySize, String.format("Code entry %d size is incorrect", entryIndex), Failure.UNSPECIFIED_MALFORMED);
        }
        module.setCodeEntries(codeEntries);
    }

    private CodeEntry readCodeEntry(int functionIndex, ByteArrayList locals, int endOffset, boolean hasNextFunction, BytecodeGen bytecode, int codeEntryIndex) {
        final WasmFunction function = module.symbolTable().function(functionIndex);
        int paramCount = function.paramCount();
        byte[] localTypes = new byte[function.paramCount() + locals.size()];
        for (int index = 0; index != paramCount; index++) {
            localTypes[index] = function.paramTypeAt(index);
        }
        for (int index = 0; index != locals.size(); index++) {
            localTypes[index + paramCount] = locals.get(index);
        }
        byte[] resultTypes = new byte[function.resultCount()];
        for (int index = 0; index != resultTypes.length; index++) {
            resultTypes[index] = function.resultTypeAt(index);
        }
        return readFunction(functionIndex, localTypes, resultTypes, endOffset, hasNextFunction, bytecode, codeEntryIndex);
    }

    private ByteArrayList readCodeEntryLocals() {
        final int localsGroupCount = readLength();
        final ByteArrayList localTypes = new ByteArrayList();
        int localsLength = 0;
        for (int localGroup = 0; localGroup != localsGroupCount; localGroup++) {
            assertTrue(!isEOF(), Failure.LENGTH_OUT_OF_BOUNDS);
            final int groupLength = readUnsignedInt32();
            localsLength += groupLength;
            module.limits().checkLocalCount(localsLength);
            final byte t = readValueType(bulkMemoryAndRefTypes);
            for (int i = 0; i != groupLength; ++i) {
                localTypes.add(t);
            }
        }
        return localTypes;
    }

    private byte[] extractBlockParamTypes(int typeIndex) {
        int paramCount = module.functionTypeParamCount(typeIndex);
        byte[] params = new byte[paramCount];
        for (int i = 0; i < paramCount; i++) {
            params[i] = module.functionTypeParamTypeAt(typeIndex, i);
        }
        return params;
    }

    private byte[] extractBlockResultTypes(int typeIndex) {
        int resultCount = module.functionTypeResultCount(typeIndex);
        byte[] results = new byte[resultCount];
        for (int i = 0; i < resultCount; i++) {
            results[i] = module.functionTypeResultTypeAt(typeIndex, i);
        }
        return results;
    }

    private static byte[] encapsulateResultType(int type) {
        switch (type) {
            case VOID_TYPE:
                return WasmType.VOID_TYPE_ARRAY;
            case I32_TYPE:
                return WasmType.I32_TYPE_ARRAY;
            case I64_TYPE:
                return WasmType.I64_TYPE_ARRAY;
            case F32_TYPE:
                return WasmType.F32_TYPE_ARRAY;
            case F64_TYPE:
                return WasmType.F64_TYPE_ARRAY;
            case FUNCREF_TYPE:
                return WasmType.FUNCREF_TYPE_ARRAY;
            case EXTERNREF_TYPE:
                return WasmType.EXTERNREF_TYPE_ARRAY;
            default:
                throw WasmException.create(Failure.UNSPECIFIED_INTERNAL);
        }
    }

    private CodeEntry readFunction(int functionIndex, byte[] locals, byte[] resultTypes, int sourceCodeEndOffset, boolean hasNextFunction, BytecodeGen bytecode, int codeEntryIndex) {
        final ParserState state = new ParserState(bytecode);
        final ArrayList<CallNode> callNodes = new ArrayList<>();
        final int bytecodeStartOffset = bytecode.location();
        state.enterFunction(resultTypes);
        int opcode;
        while (offset < sourceCodeEndOffset) {
            opcode = read1() & 0xFF;
            switch (opcode) {
                case Instructions.UNREACHABLE:
                    state.setUnreachable();
                    state.addInstruction(Bytecode.UNREACHABLE);
                    break;
                case Instructions.NOP:
                    state.addInstruction(Bytecode.NOP);
                    break;
                case Instructions.BLOCK: {
                    final byte[] blockParamTypes;
                    final byte[] blockResultTypes;
                    readBlockType(multiResult, bulkMemoryAndRefTypes);
                    // Extract value based on result arity.
                    if (multiResult[1] == SINGLE_RESULT_VALUE) {
                        blockParamTypes = WasmType.VOID_TYPE_ARRAY;
                        blockResultTypes = encapsulateResultType(multiResult[0]);
                    } else if (multiValue) {
                        blockParamTypes = extractBlockParamTypes(multiResult[0]);
                        blockResultTypes = extractBlockResultTypes(multiResult[0]);
                    } else {
                        throw WasmException.create(Failure.DISABLED_MULTI_VALUE);
                    }
                    state.popAll(blockParamTypes);
                    state.enterBlock(blockParamTypes, blockResultTypes);
                    break;
                }
                case Instructions.LOOP: {
                    // Jumps are targeting the loop instruction for OSR.
                    final byte[] loopParamTypes;
                    final byte[] loopResultTypes;
                    readBlockType(multiResult, bulkMemoryAndRefTypes);
                    // Extract value based on result arity.
                    if (multiResult[1] == SINGLE_RESULT_VALUE) {
                        loopParamTypes = WasmType.VOID_TYPE_ARRAY;
                        loopResultTypes = encapsulateResultType(multiResult[0]);
                    } else if (multiValue) {
                        loopParamTypes = extractBlockParamTypes(multiResult[0]);
                        loopResultTypes = extractBlockResultTypes(multiResult[0]);
                    } else {
                        throw WasmException.create(Failure.DISABLED_MULTI_VALUE);
                    }
                    state.popAll(loopParamTypes);
                    state.enterLoop(loopParamTypes, loopResultTypes);
                    break;
                }
                case Instructions.IF: {
                    state.popChecked(I32_TYPE); // condition
                    final byte[] ifParamTypes;
                    final byte[] ifResultTypes;
                    readBlockType(multiResult, bulkMemoryAndRefTypes);
                    // Extract value based on result arity.
                    if (multiResult[1] == SINGLE_RESULT_VALUE) {
                        ifParamTypes = WasmType.VOID_TYPE_ARRAY;
                        ifResultTypes = encapsulateResultType(multiResult[0]);
                    } else if (multiValue) {
                        ifParamTypes = extractBlockParamTypes(multiResult[0]);
                        ifResultTypes = extractBlockResultTypes(multiResult[0]);
                    } else {
                        throw WasmException.create(Failure.DISABLED_MULTI_VALUE);
                    }
                    state.popAll(ifParamTypes);
                    state.enterIf(ifParamTypes, ifResultTypes);
                    break;
                }
                case Instructions.END: {
                    state.exit(multiValue);
                    break;
                }
                case Instructions.ELSE: {
                    state.enterElse();
                    break;
                }
                case Instructions.BR: {
                    final int branchLabel = readTargetOffset();
                    state.addUnconditionalBranch(branchLabel);

                    // This instruction is stack-polymorphic
                    state.setUnreachable();
                    break;
                }
                case Instructions.BR_IF: {
                    final int branchLabel = readTargetOffset();
                    state.popChecked(I32_TYPE); // condition
                    state.addConditionalBranch(branchLabel);

                    break;
                }
                case Instructions.BR_TABLE: {
                    state.popChecked(I32_TYPE); // index
                    final int length = readLength();

                    final int[] branchTable = new int[length + 1];
                    for (int i = 0; i != length + 1; ++i) {
                        final int branchLabel = readTargetOffset();
                        branchTable[i] = branchLabel;
                    }
                    state.addBranchTable(branchTable);

                    // This instruction is stack-polymorphic
                    state.setUnreachable();
                    break;
                }
                case Instructions.RETURN: {
                    state.addReturn(multiValue);

                    // This instruction is stack-polymorphic
                    state.setUnreachable();
                    break;
                }
                case Instructions.CALL: {
                    final int callFunctionIndex = readDeclaredFunctionIndex();

                    // Pop parameters
                    final WasmFunction function = module.function(callFunctionIndex);
                    byte[] params = new byte[function.paramCount()];
                    for (int i = function.paramCount() - 1; i >= 0; --i) {
                        params[i] = function.paramTypeAt(i);
                    }
                    state.checkParamTypes(params);

                    // Push result values
                    if (!multiValue) {
                        assertIntLessOrEqual(function.resultCount(), 1, Failure.INVALID_RESULT_ARITY);
                    }
                    state.pushAll(function.type().resultTypes());
                    state.addCall(callNodes.size(), callFunctionIndex);
                    callNodes.add(new CallNode(callFunctionIndex));
                    break;
                }
                case Instructions.CALL_INDIRECT: {
                    final int expectedFunctionTypeIndex = readUnsignedInt32();
                    final int tableIndex = readTableIndex();
                    // Pop the function index to call
                    state.popChecked(I32_TYPE);
                    state.checkFunctionTypeExists(expectedFunctionTypeIndex, module.typeCount());
                    assertByteEqual(FUNCREF_TYPE, module.tableElementType(tableIndex), Failure.TYPE_MISMATCH);

                    // Pop parameters
                    for (int i = module.functionTypeParamCount(expectedFunctionTypeIndex) - 1; i >= 0; --i) {
                        state.popChecked(module.functionTypeParamTypeAt(expectedFunctionTypeIndex, i));
                    }
                    // Push result values
                    final int resultCount = module.functionTypeResultCount(expectedFunctionTypeIndex);
                    if (!multiValue) {
                        assertIntLessOrEqual(resultCount, 1, Failure.INVALID_RESULT_ARITY);
                    }
                    byte[] callResultTypes = new byte[resultCount];
                    for (int i = 0; i < resultCount; i++) {
                        callResultTypes[i] = module.functionTypeResultTypeAt(expectedFunctionTypeIndex, i);
                    }
                    state.pushAll(callResultTypes);
                    state.addIndirectCall(callNodes.size(), expectedFunctionTypeIndex, tableIndex);
                    callNodes.add(new CallNode());
                    break;
                }
                case Instructions.DROP:
                    final byte type = state.pop();
                    if (WasmType.isNumberType(type)) {
                        state.addInstruction(Bytecode.DROP);
                    } else {
                        state.addInstruction(Bytecode.DROP_REF);
                    }
                    break;
                case Instructions.SELECT: {
                    state.popChecked(I32_TYPE); // condition
                    final byte t1 = state.pop(); // first operand
                    final byte t2 = state.pop(); // second operand
                    assertTrue(WasmType.isNumberType(t1) && WasmType.isNumberType(t2), Failure.TYPE_MISMATCH);
                    assertTrue(t1 == t2 || t1 == WasmType.UNKNOWN_TYPE || t2 == WasmType.UNKNOWN_TYPE, Failure.TYPE_MISMATCH);
                    state.push(t1 == WasmType.UNKNOWN_TYPE ? t2 : t1);
                    state.addInstruction(Bytecode.SELECT);
                    break;
                }
                case Instructions.SELECT_T: {
                    checkBulkMemoryAndRefTypesSupport(opcode);
                    final int length = readLength();
                    assertIntEqual(length, 1, Failure.INVALID_RESULT_ARITY);
                    final byte t = readValueType(bulkMemoryAndRefTypes);
                    state.popChecked(I32_TYPE);
                    state.popChecked(t);
                    state.popChecked(t);
                    state.push(t);
                    if (WasmType.isNumberType(t)) {
                        state.addInstruction(Bytecode.SELECT);
                    } else {
                        state.addInstruction(Bytecode.SELECT_REF);
                    }
                    break;
                }
                case Instructions.LOCAL_GET:
                case Instructions.LOCAL_GET_REF: {
                    final int localIndex = readLocalIndex();
                    assertUnsignedIntLess(localIndex, locals.length, Failure.UNKNOWN_LOCAL);
                    final byte localType = locals[localIndex];
                    state.push(localType);
                    if (WasmType.isNumberType(localType)) {
                        state.addUnsignedInstruction(Bytecode.LOCAL_GET_U8, localIndex);
                    } else {
                        state.addUnsignedInstruction(Bytecode.LOCAL_GET_REF_U8, localIndex);
                    }
                    break;
                }
                case Instructions.LOCAL_SET:
                case Instructions.LOCAL_SET_REF: {
                    final int localIndex = readLocalIndex();
                    assertUnsignedIntLess(localIndex, locals.length, Failure.UNKNOWN_LOCAL);
                    final byte localType = locals[localIndex];
                    state.popChecked(localType);
                    if (WasmType.isNumberType(localType)) {
                        state.addUnsignedInstruction(Bytecode.LOCAL_SET_U8, localIndex);
                    } else {
                        state.addUnsignedInstruction(Bytecode.LOCAL_SET_REF_U8, localIndex);
                    }
                    break;
                }
                case Instructions.LOCAL_TEE:
                case Instructions.LOCAL_TEE_REF: {
                    final int localIndex = readLocalIndex();
                    assertUnsignedIntLess(localIndex, locals.length, Failure.UNKNOWN_LOCAL);
                    final byte localType = locals[localIndex];
                    state.popChecked(localType);
                    state.push(localType);
                    if (WasmType.isNumberType(localType)) {
                        state.addUnsignedInstruction(Bytecode.LOCAL_TEE_U8, localIndex);
                    } else {
                        state.addUnsignedInstruction(Bytecode.LOCAL_TEE_REF_U8, localIndex);
                    }
                    break;
                }
                case Instructions.GLOBAL_GET: {
                    final int index = readGlobalIndex();
                    state.push(module.symbolTable().globalValueType(index));
                    state.addUnsignedInstruction(Bytecode.GLOBAL_GET_U8, index);
                    break;
                }
                case Instructions.GLOBAL_SET: {
                    final int index = readGlobalIndex();
                    // Assert that the global is mutable.
                    assertByteEqual(module.symbolTable().globalMutability(index), GlobalModifier.MUTABLE,
                                    "Immutable globals cannot be set: " + index, Failure.IMMUTABLE_GLOBAL_WRITE);
                    state.popChecked(module.symbolTable().globalValueType(index));
                    state.addUnsignedInstruction(Bytecode.GLOBAL_SET_U8, index);
                    break;
                }
                case Instructions.TABLE_GET: {
                    checkBulkMemoryAndRefTypesSupport(opcode);
                    final int index = readTableIndex();
                    final byte elementType = module.tableElementType(index);
                    state.popChecked(I32_TYPE);
                    state.push(elementType);
                    state.addInstruction(Bytecode.TABLE_GET, index);
                    break;
                }
                case Instructions.TABLE_SET: {
                    checkBulkMemoryAndRefTypesSupport(opcode);
                    final int index = readTableIndex();
                    final byte elementType = module.tableElementType(index);
                    state.popChecked(elementType);
                    state.popChecked(I32_TYPE);
                    state.addInstruction(Bytecode.TABLE_SET, index);
                    break;
                }
                case Instructions.F32_LOAD:
                    state.addMemoryInstruction(Bytecode.F32_LOAD, load(state, F32_TYPE, 32), module.memoryHasIndexType64());
                    break;
                case Instructions.F64_LOAD:
                    state.addMemoryInstruction(Bytecode.F64_LOAD, load(state, F64_TYPE, 64), module.memoryHasIndexType64());
                    break;
                case Instructions.I32_LOAD:
                    state.addMemoryInstruction(Bytecode.I32_LOAD, load(state, I32_TYPE, 32), module.memoryHasIndexType64());
                    break;
                case Instructions.I32_LOAD8_S:
                    state.addMemoryInstruction(Bytecode.I32_LOAD8_S, load(state, I32_TYPE, 8), module.memoryHasIndexType64());
                    break;
                case Instructions.I32_LOAD8_U:
                    state.addMemoryInstruction(Bytecode.I32_LOAD8_U, load(state, I32_TYPE, 8), module.memoryHasIndexType64());
                    break;
                case Instructions.I32_LOAD16_S:
                    state.addMemoryInstruction(Bytecode.I32_LOAD16_S, load(state, I32_TYPE, 16), module.memoryHasIndexType64());
                    break;
                case Instructions.I32_LOAD16_U:
                    state.addMemoryInstruction(Bytecode.I32_LOAD16_U, load(state, I32_TYPE, 16), module.memoryHasIndexType64());
                    break;
                case Instructions.I64_LOAD:
                    state.addMemoryInstruction(Bytecode.I64_LOAD, load(state, I64_TYPE, 64), module.memoryHasIndexType64());
                    break;
                case Instructions.I64_LOAD8_S:
                    state.addMemoryInstruction(Bytecode.I64_LOAD8_S, load(state, I64_TYPE, 8), module.memoryHasIndexType64());
                    break;
                case Instructions.I64_LOAD8_U:
                    state.addMemoryInstruction(Bytecode.I64_LOAD8_U, load(state, I64_TYPE, 8), module.memoryHasIndexType64());
                    break;
                case Instructions.I64_LOAD16_S:
                    state.addMemoryInstruction(Bytecode.I64_LOAD16_S, load(state, I64_TYPE, 16), module.memoryHasIndexType64());
                    break;
                case Instructions.I64_LOAD16_U:
                    state.addMemoryInstruction(Bytecode.I64_LOAD16_U, load(state, I64_TYPE, 16), module.memoryHasIndexType64());
                    break;
                case Instructions.I64_LOAD32_S:
                    state.addMemoryInstruction(Bytecode.I64_LOAD32_S, load(state, I64_TYPE, 32), module.memoryHasIndexType64());
                    break;
                case Instructions.I64_LOAD32_U:
                    state.addMemoryInstruction(Bytecode.I64_LOAD32_U, load(state, I64_TYPE, 32), module.memoryHasIndexType64());
                    break;
                case Instructions.F32_STORE:
                    state.addMemoryInstruction(Bytecode.F32_STORE, store(state, F32_TYPE, 32), module.memoryHasIndexType64());
                    break;
                case Instructions.F64_STORE:
                    state.addMemoryInstruction(Bytecode.F64_STORE, store(state, F64_TYPE, 64), module.memoryHasIndexType64());
                    break;
                case Instructions.I32_STORE:
                    state.addMemoryInstruction(Bytecode.I32_STORE, store(state, I32_TYPE, 32), module.memoryHasIndexType64());
                    break;
                case Instructions.I32_STORE_8:
                    state.addMemoryInstruction(Bytecode.I32_STORE_8, store(state, I32_TYPE, 8), module.memoryHasIndexType64());
                    break;
                case Instructions.I32_STORE_16:
                    state.addMemoryInstruction(Bytecode.I32_STORE_16, store(state, I32_TYPE, 16), module.memoryHasIndexType64());
                    break;
                case Instructions.I64_STORE:
                    state.addMemoryInstruction(Bytecode.I64_STORE, store(state, I64_TYPE, 64), module.memoryHasIndexType64());
                    break;
                case Instructions.I64_STORE_8:
                    state.addMemoryInstruction(Bytecode.I64_STORE_8, store(state, I64_TYPE, 8), module.memoryHasIndexType64());
                    break;
                case Instructions.I64_STORE_16:
                    state.addMemoryInstruction(Bytecode.I64_STORE_16, store(state, I64_TYPE, 16), module.memoryHasIndexType64());
                    break;
                case Instructions.I64_STORE_32:
                    state.addMemoryInstruction(Bytecode.I64_STORE_32, store(state, I64_TYPE, 32), module.memoryHasIndexType64());
                    break;
                case Instructions.MEMORY_SIZE: {
                    final int flag = read1();
                    assertIntEqual(flag, 0, Failure.ZERO_BYTE_EXPECTED);
                    checkMemoryIndex(0);
                    if (module.memoryHasIndexType64() && memory64) {
                        state.push(I64_TYPE);
                        state.addMiscFlag();
                        state.addInstruction(Bytecode.MEMORY64_SIZE);
                    } else {
                        state.push(I32_TYPE);
                        state.addInstruction(Bytecode.MEMORY_SIZE);
                    }
                    break;
                }
                case Instructions.MEMORY_GROW: {
                    final int flag = read1();
                    assertIntEqual(flag, 0, Failure.ZERO_BYTE_EXPECTED);
                    checkMemoryIndex(0);
                    if (module.memoryHasIndexType64() && memory64) {
                        state.popChecked(I64_TYPE);
                        state.push(I64_TYPE);
                        state.addMiscFlag();
                        state.addInstruction(Bytecode.MEMORY64_GROW);
                    } else {
                        state.popChecked(I32_TYPE);
                        state.push(I32_TYPE);
                        state.addInstruction(Bytecode.MEMORY_GROW);
                    }
                    break;
                }
                default:
                    readNumericInstructions(state, opcode);
                    break;

            }
        }
        assertIntEqual(state.valueStackSize(), resultTypes.length,
                        "Stack size must match the return type length at the function end", Failure.TYPE_MISMATCH);
        if (hasNextFunction) {
            assertIntEqual(state.controlStackSize(), 0, Failure.END_OPCODE_EXPECTED);
        } else {
            if (state.controlStackSize() != 0) {
                // Check if we reached the end of the binary
                peek1();
                fail(Failure.SECTION_SIZE_MISMATCH, "END opcode expected");
            }
        }
        final int bytecodeEndOffset = bytecode.location();
        bytecode.addCodeEntry(functionIndex, state.maxStackSize(), bytecodeEndOffset - bytecodeStartOffset, locals.length, resultTypes.length);
        for (byte local : locals) {
            bytecode.addByte(local);
        }
        if (locals.length != 0) {
            bytecode.addByte((byte) 0);
        }
        for (byte result : resultTypes) {
            bytecode.addByte(result);
        }
        if (resultTypes.length != 0) {
            bytecode.addByte((byte) 0);
        }
        module.setCodeEntryOffset(codeEntryIndex, bytecodeEndOffset);
        return new CodeEntry(functionIndex, state.maxStackSize(), locals, resultTypes, callNodes, bytecodeStartOffset, bytecodeEndOffset);
    }

    private void readNumericInstructions(ParserState state, int opcode) {
        switch (opcode) {
            case Instructions.I32_CONST: {
                final int value = readSignedInt32();
                state.push(I32_TYPE);
                state.addSignedInstruction(Bytecode.I32_CONST_I8, value);
                break;
            }
            case Instructions.I64_CONST: {
                final long value = readSignedInt64();
                state.push(I64_TYPE);
                state.addSignedInstruction(Bytecode.I64_CONST_I8, value);
                break;
            }
            case Instructions.F32_CONST: {
                final int value = read4();
                state.push(F32_TYPE);
                state.addInstruction(Bytecode.F32_CONST, value);
                break;
            }
            case Instructions.F64_CONST: {
                final long value = read8();
                state.push(F64_TYPE);
                state.addInstruction(Bytecode.F64_CONST, value);
                break;
            }
            case Instructions.I32_EQZ:
                state.popChecked(I32_TYPE);
                state.push(I32_TYPE);
                state.addInstruction(Bytecode.I32_EQZ);
                break;
            case Instructions.I32_EQ:
            case Instructions.I32_NE:
            case Instructions.I32_LT_S:
            case Instructions.I32_LT_U:
            case Instructions.I32_GT_S:
            case Instructions.I32_GT_U:
            case Instructions.I32_LE_S:
            case Instructions.I32_LE_U:
            case Instructions.I32_GE_S:
            case Instructions.I32_GE_U:
            case Instructions.I32_ADD:
            case Instructions.I32_SUB:
            case Instructions.I32_MUL:
            case Instructions.I32_DIV_S:
            case Instructions.I32_DIV_U:
            case Instructions.I32_REM_S:
            case Instructions.I32_REM_U:
            case Instructions.I32_AND:
            case Instructions.I32_OR:
            case Instructions.I32_XOR:
            case Instructions.I32_SHL:
            case Instructions.I32_SHR_S:
            case Instructions.I32_SHR_U:
            case Instructions.I32_ROTL:
            case Instructions.I32_ROTR:
                state.popChecked(I32_TYPE);
                state.popChecked(I32_TYPE);
                state.push(I32_TYPE);
                state.addInstruction(opcode + Bytecode.COMMON_BYTECODE_OFFSET);
                break;
            case Instructions.I64_EQZ:
                state.popChecked(I64_TYPE);
                state.push(I32_TYPE);
                state.addInstruction(Bytecode.I64_EQZ);
                break;
            case Instructions.I64_EQ:
            case Instructions.I64_NE:
            case Instructions.I64_LT_S:
            case Instructions.I64_LT_U:
            case Instructions.I64_GT_S:
            case Instructions.I64_GT_U:
            case Instructions.I64_LE_S:
            case Instructions.I64_LE_U:
            case Instructions.I64_GE_S:
            case Instructions.I64_GE_U:
                state.popChecked(I64_TYPE);
                state.popChecked(I64_TYPE);
                state.push(I32_TYPE);
                state.addInstruction(opcode + Bytecode.COMMON_BYTECODE_OFFSET);
                break;
            case Instructions.F32_EQ:
            case Instructions.F32_NE:
            case Instructions.F32_LT:
            case Instructions.F32_GT:
            case Instructions.F32_LE:
            case Instructions.F32_GE:
                state.popChecked(F32_TYPE);
                state.popChecked(F32_TYPE);
                state.push(I32_TYPE);
                state.addInstruction(opcode + Bytecode.COMMON_BYTECODE_OFFSET);
                break;
            case Instructions.F64_EQ:
            case Instructions.F64_NE:
            case Instructions.F64_LT:
            case Instructions.F64_GT:
            case Instructions.F64_LE:
            case Instructions.F64_GE:
                state.popChecked(F64_TYPE);
                state.popChecked(F64_TYPE);
                state.push(I32_TYPE);
                state.addInstruction(opcode + Bytecode.COMMON_BYTECODE_OFFSET);
                break;
            case Instructions.I32_CLZ:
            case Instructions.I32_CTZ:
            case Instructions.I32_POPCNT:
                state.popChecked(I32_TYPE);
                state.push(I32_TYPE);
                state.addInstruction(opcode + Bytecode.COMMON_BYTECODE_OFFSET);
                break;
            case Instructions.I64_CLZ:
            case Instructions.I64_CTZ:
            case Instructions.I64_POPCNT:
                state.popChecked(I64_TYPE);
                state.push(I64_TYPE);
                state.addInstruction(opcode + Bytecode.COMMON_BYTECODE_OFFSET);
                break;
            case Instructions.I64_ADD:
            case Instructions.I64_SUB:
            case Instructions.I64_MUL:
            case Instructions.I64_DIV_S:
            case Instructions.I64_DIV_U:
            case Instructions.I64_REM_S:
            case Instructions.I64_REM_U:
            case Instructions.I64_AND:
            case Instructions.I64_OR:
            case Instructions.I64_XOR:
            case Instructions.I64_SHL:
            case Instructions.I64_SHR_S:
            case Instructions.I64_SHR_U:
            case Instructions.I64_ROTL:
            case Instructions.I64_ROTR:
                state.popChecked(I64_TYPE);
                state.popChecked(I64_TYPE);
                state.push(I64_TYPE);
                state.addInstruction(opcode + Bytecode.COMMON_BYTECODE_OFFSET);
                break;
            case Instructions.F32_ABS:
            case Instructions.F32_NEG:
            case Instructions.F32_CEIL:
            case Instructions.F32_FLOOR:
            case Instructions.F32_TRUNC:
            case Instructions.F32_NEAREST:
            case Instructions.F32_SQRT:
                state.popChecked(F32_TYPE);
                state.push(F32_TYPE);
                state.addInstruction(opcode + Bytecode.COMMON_BYTECODE_OFFSET);
                break;
            case Instructions.F32_ADD:
            case Instructions.F32_SUB:
            case Instructions.F32_MUL:
            case Instructions.F32_DIV:
            case Instructions.F32_MIN:
            case Instructions.F32_MAX:
            case Instructions.F32_COPYSIGN:
                state.popChecked(F32_TYPE);
                state.popChecked(F32_TYPE);
                state.push(F32_TYPE);
                state.addInstruction(opcode + Bytecode.COMMON_BYTECODE_OFFSET);
                break;
            case Instructions.F64_ABS:
            case Instructions.F64_NEG:
            case Instructions.F64_CEIL:
            case Instructions.F64_FLOOR:
            case Instructions.F64_TRUNC:
            case Instructions.F64_NEAREST:
            case Instructions.F64_SQRT:
                state.popChecked(F64_TYPE);
                state.push(F64_TYPE);
                state.addInstruction(opcode + Bytecode.COMMON_BYTECODE_OFFSET);
                break;
            case Instructions.F64_ADD:
            case Instructions.F64_SUB:
            case Instructions.F64_MUL:
            case Instructions.F64_DIV:
            case Instructions.F64_MIN:
            case Instructions.F64_MAX:
            case Instructions.F64_COPYSIGN:
                state.popChecked(F64_TYPE);
                state.popChecked(F64_TYPE);
                state.push(F64_TYPE);
                state.addInstruction(opcode + Bytecode.COMMON_BYTECODE_OFFSET);
                break;
            case Instructions.I32_WRAP_I64:
                state.popChecked(I64_TYPE);
                state.push(I32_TYPE);
                state.addInstruction(Bytecode.I32_WRAP_I64);
                break;
            case Instructions.I32_TRUNC_F32_S:
            case Instructions.I32_TRUNC_F32_U:
            case Instructions.I32_REINTERPRET_F32:
                state.popChecked(F32_TYPE);
                state.push(I32_TYPE);
                state.addInstruction(opcode + Bytecode.COMMON_BYTECODE_OFFSET);
                break;
            case Instructions.I32_TRUNC_F64_S:
            case Instructions.I32_TRUNC_F64_U:
                state.popChecked(F64_TYPE);
                state.push(I32_TYPE);
                state.addInstruction(opcode + Bytecode.COMMON_BYTECODE_OFFSET);
                break;
            case Instructions.I64_EXTEND_I32_S:
            case Instructions.I64_EXTEND_I32_U:
                state.popChecked(I32_TYPE);
                state.push(I64_TYPE);
                state.addInstruction(opcode + Bytecode.COMMON_BYTECODE_OFFSET);
                break;
            case Instructions.I64_TRUNC_F32_S:
            case Instructions.I64_TRUNC_F32_U:
                state.popChecked(F32_TYPE);
                state.push(I64_TYPE);
                state.addInstruction(opcode + Bytecode.COMMON_BYTECODE_OFFSET);
                break;
            case Instructions.I64_TRUNC_F64_S:
            case Instructions.I64_TRUNC_F64_U:
            case Instructions.I64_REINTERPRET_F64:
                state.popChecked(F64_TYPE);
                state.push(I64_TYPE);
                state.addInstruction(opcode + Bytecode.COMMON_BYTECODE_OFFSET);
                break;
            case Instructions.F32_CONVERT_I32_S:
            case Instructions.F32_CONVERT_I32_U:
            case Instructions.F32_REINTERPRET_I32:
                state.popChecked(I32_TYPE);
                state.push(F32_TYPE);
                state.addInstruction(opcode + Bytecode.COMMON_BYTECODE_OFFSET);
                break;
            case Instructions.F32_CONVERT_I64_S:
            case Instructions.F32_CONVERT_I64_U:
                state.popChecked(I64_TYPE);
                state.push(F32_TYPE);
                state.addInstruction(opcode + Bytecode.COMMON_BYTECODE_OFFSET);
                break;
            case Instructions.F32_DEMOTE_F64:
                state.popChecked(F64_TYPE);
                state.push(F32_TYPE);
                state.addInstruction(opcode + Bytecode.COMMON_BYTECODE_OFFSET);
                break;
            case Instructions.F64_CONVERT_I32_S:
            case Instructions.F64_CONVERT_I32_U:
                state.popChecked(I32_TYPE);
                state.push(F64_TYPE);
                state.addInstruction(opcode + Bytecode.COMMON_BYTECODE_OFFSET);
                break;
            case Instructions.F64_CONVERT_I64_S:
            case Instructions.F64_CONVERT_I64_U:
            case Instructions.F64_REINTERPRET_I64:
                state.popChecked(I64_TYPE);
                state.push(F64_TYPE);
                state.addInstruction(opcode + Bytecode.COMMON_BYTECODE_OFFSET);
                break;
            case Instructions.F64_PROMOTE_F32:
                state.popChecked(F32_TYPE);
                state.push(F64_TYPE);
                state.addInstruction(opcode + Bytecode.COMMON_BYTECODE_OFFSET);
                break;
            case Instructions.MISC:
                int miscOpcode = readUnsignedInt32();
                switch (miscOpcode) {
                    case Instructions.I32_TRUNC_SAT_F32_S:
                    case Instructions.I32_TRUNC_SAT_F32_U:
                        checkSaturatingFloatToIntSupport(miscOpcode);
                        state.popChecked(F32_TYPE);
                        state.push(I32_TYPE);
                        state.addMiscFlag();
                        state.addInstruction(miscOpcode);
                        break;
                    case Instructions.I32_TRUNC_SAT_F64_S:
                    case Instructions.I32_TRUNC_SAT_F64_U:
                        checkSaturatingFloatToIntSupport(miscOpcode);
                        state.popChecked(F64_TYPE);
                        state.push(I32_TYPE);
                        state.addMiscFlag();
                        state.addInstruction(miscOpcode);
                        break;
                    case Instructions.I64_TRUNC_SAT_F32_S:
                    case Instructions.I64_TRUNC_SAT_F32_U:
                        checkSaturatingFloatToIntSupport(miscOpcode);
                        state.popChecked(F32_TYPE);
                        state.push(I64_TYPE);
                        state.addMiscFlag();
                        state.addInstruction(miscOpcode);
                        break;
                    case Instructions.I64_TRUNC_SAT_F64_S:
                    case Instructions.I64_TRUNC_SAT_F64_U:
                        checkSaturatingFloatToIntSupport(miscOpcode);
                        state.popChecked(F64_TYPE);
                        state.push(I64_TYPE);
                        state.addMiscFlag();
                        state.addInstruction(miscOpcode);
                        break;
                    case Instructions.MEMORY_INIT: {
                        checkBulkMemoryAndRefTypesSupport(miscOpcode);
                        final int dataIndex = readUnsignedInt32();
                        readMemoryIndex();
                        module.checkDataSegmentIndex(dataIndex);
                        state.popChecked(I32_TYPE);
                        state.popChecked(I32_TYPE);
                        if (module.memoryHasIndexType64() && memory64) {
                            state.popChecked(I64_TYPE);
                            state.addMiscFlag();
                            if (unsafeMemory) {
                                state.addInstruction(Bytecode.MEMORY64_INIT_UNSAFE, dataIndex);
                            } else {
                                state.addInstruction(Bytecode.MEMORY64_INIT, dataIndex);
                            }
                        } else {
                            state.popChecked(I32_TYPE);
                            state.addMiscFlag();
                            if (unsafeMemory) {
                                state.addInstruction(Bytecode.MEMORY_INIT_UNSAFE, dataIndex);
                            } else {
                                state.addInstruction(Bytecode.MEMORY_INIT, dataIndex);
                            }
                        }
                        break;
                    }
                    case Instructions.DATA_DROP: {
                        checkBulkMemoryAndRefTypesSupport(miscOpcode);
                        final int dataIndex = readUnsignedInt32();
                        module.checkDataSegmentIndex(dataIndex);
                        state.addMiscFlag();
                        if (unsafeMemory) {
                            state.addInstruction(Bytecode.DATA_DROP_UNSAFE, dataIndex);
                        } else {
                            state.addInstruction(Bytecode.DATA_DROP, dataIndex);
                        }
                        break;
                    }
                    case Instructions.MEMORY_COPY: {
                        checkBulkMemoryAndRefTypesSupport(miscOpcode);
                        readMemoryIndex();
                        readMemoryIndex();
                        if (module.memoryHasIndexType64() && memory64) {
                            state.popChecked(I64_TYPE);
                            state.popChecked(I64_TYPE);
                            state.popChecked(I64_TYPE);
                            state.addMiscFlag();
                            state.addInstruction(Bytecode.MEMORY64_COPY);
                        } else {
                            state.popChecked(I32_TYPE);
                            state.popChecked(I32_TYPE);
                            state.popChecked(I32_TYPE);
                            state.addMiscFlag();
                            state.addInstruction(Bytecode.MEMORY_COPY);
                        }
                        break;
                    }
                    case Instructions.MEMORY_FILL: {
                        checkBulkMemoryAndRefTypesSupport(miscOpcode);
                        readMemoryIndex();
                        if (module.memoryHasIndexType64() && memory64) {
                            state.popChecked(I64_TYPE);
                            state.popChecked(I32_TYPE);
                            state.popChecked(I64_TYPE);
                            state.addMiscFlag();
                            state.addInstruction(Bytecode.MEMORY64_FILL);
                        } else {
                            state.popChecked(I32_TYPE);
                            state.popChecked(I32_TYPE);
                            state.popChecked(I32_TYPE);
                            state.addMiscFlag();
                            state.addInstruction(Bytecode.MEMORY_FILL);
                        }
                        break;
                    }
                    case Instructions.TABLE_INIT: {
                        checkBulkMemoryAndRefTypesSupport(miscOpcode);
                        final int elementIndex = readUnsignedInt32();
                        final int tableIndex = readTableIndex();
                        module.checkElemIndex(elementIndex);
                        final byte elementType = module.tableElementType(tableIndex);
                        module.checkElemType(elementIndex, elementType);
                        state.popChecked(I32_TYPE);
                        state.popChecked(I32_TYPE);
                        state.popChecked(I32_TYPE);
                        state.addMiscFlag();
                        state.addInstruction(Bytecode.TABLE_INIT, elementIndex, tableIndex);
                        break;
                    }
                    case Instructions.ELEM_DROP: {
                        checkBulkMemoryAndRefTypesSupport(miscOpcode);
                        final int elementIndex = readUnsignedInt32();
                        module.checkElemIndex(elementIndex);
                        state.addMiscFlag();
                        state.addInstruction(Bytecode.ELEM_DROP, elementIndex);
                        break;
                    }
                    case Instructions.TABLE_COPY:
                        checkBulkMemoryAndRefTypesSupport(miscOpcode);
                        final int destinationTableIndex = readTableIndex();
                        final byte destinationElementType = module.tableElementType(destinationTableIndex);
                        final int sourceTableIndex = readTableIndex();
                        final byte sourceElementType = module.tableElementType(sourceTableIndex);
                        assertByteEqual(sourceElementType, destinationElementType, Failure.TYPE_MISMATCH);
                        state.popChecked(I32_TYPE);
                        state.popChecked(I32_TYPE);
                        state.popChecked(I32_TYPE);
                        state.addMiscFlag();
                        state.addInstruction(Bytecode.TABLE_COPY, sourceTableIndex, destinationTableIndex);
                        break;
                    case Instructions.TABLE_SIZE: {
                        checkBulkMemoryAndRefTypesSupport(miscOpcode);
                        final int tableIndex = readTableIndex();
                        state.push(I32_TYPE);
                        state.addMiscFlag();
                        state.addInstruction(Bytecode.TABLE_SIZE, tableIndex);
                        break;
                    }
                    case Instructions.TABLE_GROW: {
                        checkBulkMemoryAndRefTypesSupport(miscOpcode);
                        final int tableIndex = readTableIndex();
                        final byte elementType = module.tableElementType(tableIndex);
                        state.popChecked(I32_TYPE);
                        state.popChecked(elementType);
                        state.push(I32_TYPE);
                        state.addMiscFlag();
                        state.addInstruction(Bytecode.TABLE_GROW, tableIndex);
                        break;
                    }
                    case Instructions.TABLE_FILL: {
                        checkBulkMemoryAndRefTypesSupport(miscOpcode);
                        final int tableIndex = readTableIndex();
                        final byte elementType = module.tableElementType(tableIndex);
                        state.popChecked(I32_TYPE);
                        state.popChecked(elementType);
                        state.popChecked(I32_TYPE);
                        state.addMiscFlag();
                        state.addInstruction(Bytecode.TABLE_FILL, tableIndex);
                        break;
                    }
                    default:
                        fail(Failure.UNSPECIFIED_MALFORMED, "Unknown opcode: 0xFC 0x%02x", miscOpcode);
                }
                break;
            case Instructions.I32_EXTEND8_S:
            case Instructions.I32_EXTEND16_S:
                checkSignExtensionOpsSupport(opcode);
                state.popChecked(I32_TYPE);
                state.push(I32_TYPE);
                state.addInstruction(opcode + Bytecode.COMMON_BYTECODE_OFFSET);
                break;
            case Instructions.I64_EXTEND8_S:
            case Instructions.I64_EXTEND16_S:
            case Instructions.I64_EXTEND32_S:
                checkSignExtensionOpsSupport(opcode);
                state.popChecked(I64_TYPE);
                state.push(I64_TYPE);
                state.addInstruction(opcode + Bytecode.COMMON_BYTECODE_OFFSET);
                break;
            case Instructions.REF_NULL:
                checkBulkMemoryAndRefTypesSupport(opcode);
                final byte type = readRefType();
                state.push(type);
                state.addInstruction(Bytecode.REF_NULL);
                break;
            case Instructions.REF_IS_NULL:
                checkBulkMemoryAndRefTypesSupport(opcode);
                state.popReferenceTypeChecked();
                state.push(I32_TYPE);
                state.addInstruction(Bytecode.REF_IS_NULL);
                break;
            case Instructions.REF_FUNC:
                checkBulkMemoryAndRefTypesSupport(opcode);
                final int functionIndex = readDeclaredFunctionIndex();
                module.checkFunctionReference(functionIndex);
                state.push(FUNCREF_TYPE);
                state.addInstruction(Bytecode.REF_FUNC, functionIndex);
                break;
            default:
                fail(Failure.UNSPECIFIED_MALFORMED, "Unknown opcode: 0x%02x", opcode);
                break;
        }
    }

    private static void checkContextOption(boolean option, String message, Object... args) {
        if (!option) {
            fail(Failure.UNSPECIFIED_MALFORMED, message, args);
        }
    }

    private void checkSaturatingFloatToIntSupport(int opcode) {
        checkContextOption(wasmContext.getContextOptions().supportSaturatingFloatToInt(), "Saturating float-to-int conversion is not enabled (opcode: 0xFC 0x%02x)", opcode);
    }

    private void checkSignExtensionOpsSupport(int opcode) {
        checkContextOption(wasmContext.getContextOptions().supportSignExtensionOps(), "Sign-extension operators are not enabled (opcode: 0x%02x)", opcode);
    }

    private void checkBulkMemoryAndRefTypesSupport(int opcode) {
        checkContextOption(wasmContext.getContextOptions().supportBulkMemoryAndRefTypes(), "Bulk memory operations and reference types are not enabled (opcode: 0x%02x)", opcode);
    }

    private long store(ParserState state, byte type, int n) {
        assertTrue(module.memoryExists(), Failure.UNKNOWN_MEMORY);
        readAlignHint(n); // align hint
        final long memoryOffset;
        if (memory64) {
            memoryOffset = readUnsignedInt64(); // 64-bit store offset
        } else {
            memoryOffset = Integer.toUnsignedLong(readUnsignedInt32()); // 32-bit store offset
        }
        state.popChecked(type); // value to store
        if (module.memoryHasIndexType64() && memory64) {
            state.popChecked(I64_TYPE);
        } else {
            state.popChecked(I32_TYPE); // 32-bit base address
        }
        return memoryOffset;
    }

    private long load(ParserState state, byte type, int n) {
        assertTrue(module.memoryExists(), Failure.UNKNOWN_MEMORY);
        readAlignHint(n); // align hint
        final long memoryOffset;
        if (memory64) {
            memoryOffset = readUnsignedInt64(); // 64-bit load offset
        } else {
            memoryOffset = Integer.toUnsignedLong(readUnsignedInt32()); // 32-bit load offset
        }
        if (module.memoryHasIndexType64() && memory64) {
            state.popChecked(I64_TYPE); // 64-bit base address
        } else {
            state.popChecked(I32_TYPE); // 32-bit base address
        }
        state.push(type); // loaded value
        return memoryOffset;
    }

    private void readOffsetExpression(int[] result) {
        // Table offset expression must be a constant expression with result type i32.
        // https://webassembly.github.io/spec/core/syntax/modules.html#element-segments
        // https://webassembly.github.io/spec/core/valid/instructions.html#constant-expressions

        // Read the offset expression.
        byte instruction = read1();

        // Read the offset expression.
        int offsetAddress = -1;
        int offsetGlobalIndex = -1;
        switch (instruction) {
            case Instructions.I32_CONST:
                offsetAddress = readSignedInt32();
                break;
            case Instructions.GLOBAL_GET:
                offsetGlobalIndex = readGlobalIndex();
                assertIntEqual(module.globalMutability(offsetGlobalIndex), GlobalModifier.CONSTANT, Failure.CONSTANT_EXPRESSION_REQUIRED);
                assertByteEqual(module.globalValueType(offsetGlobalIndex), I32_TYPE, Failure.TYPE_MISMATCH);
                break;
            default:
                throw WasmException.format(Failure.TYPE_MISMATCH, "Invalid instruction for offset expression: 0x%02X", instruction);
        }
        result[0] = offsetAddress;
        result[1] = offsetGlobalIndex;
        readEnd();
    }

    private void readLongOffsetExpression(long[] result) {
        byte instruction = read1();
        long offsetAddress = -1L;
        int offsetGlobalIndex = -1;
        switch (instruction) {
            case Instructions.I64_CONST:
                offsetAddress = readSignedInt64();
                break;
            case Instructions.GLOBAL_GET:
                offsetGlobalIndex = readGlobalIndex();
                assertIntEqual(module.globalMutability(offsetGlobalIndex), GlobalModifier.CONSTANT, Failure.CONSTANT_EXPRESSION_REQUIRED);
                assertByteEqual(module.globalValueType(offsetGlobalIndex), I64_TYPE, Failure.TYPE_MISMATCH);
                break;
            default:
                throw WasmException.format(Failure.TYPE_MISMATCH, "Invalid instruction for offset expression: 0x%02X", instruction);
        }
        result[0] = offsetAddress;
        result[1] = offsetGlobalIndex;
    }

    private long[] readFunctionIndices() {
        final int functionIndexCount = readLength();
        final long[] functionIndices = new long[functionIndexCount];
        for (int index = 0; index != functionIndexCount; index++) {
            assertTrue(!isEOF(), Failure.LENGTH_OUT_OF_BOUNDS);
            final int functionIndex = readDeclaredFunctionIndex();
            module.addFunctionReference(functionIndex);
            functionIndices[index] = ((long) FUNCREF_TYPE << 32) | functionIndex;
        }
        return functionIndices;
    }

    private void checkElemKind() {
        final byte elementKind = read1();
        if (elementKind != 0x00) {
            throw WasmException.format(Failure.TYPE_MISMATCH, "Invalid element kind: 0x%02X", elementKind);
        }
    }

    private long[] readElemExpressions(byte elemType) {
        final int expressionCount = readLength();
        final long[] functionIndices = new long[expressionCount];
        for (int index = 0; index != expressionCount; index++) {
            assertTrue(!isEOF(), Failure.LENGTH_OUT_OF_BOUNDS);
            int opcode = read1() & 0xFF;
            switch (opcode) {
                case Instructions.I32_CONST:
                case Instructions.I64_CONST:
                case Instructions.F32_CONST:
                case Instructions.F64_CONST:
                    throw WasmException.format(Failure.TYPE_MISMATCH, "Invalid constant expression for table elem expression: 0x%02X", opcode);
                case Instructions.REF_NULL:
                    final byte type = readRefType();
                    if (bulkMemoryAndRefTypes && type != elemType) {
                        fail(Failure.TYPE_MISMATCH, "Invalid ref.null type: 0x%02X", type);
                    }
                    functionIndices[index] = ((long) NULL_TYPE << 32);
                    break;
                case Instructions.REF_FUNC:
                    if (elemType != FUNCREF_TYPE) {
                        fail(Failure.TYPE_MISMATCH, "Invalid element type: 0x%02X", FUNCREF_TYPE);
                    }
                    final int functionIndex = readDeclaredFunctionIndex();
                    module.addFunctionReference(functionIndex);
                    functionIndices[index] = ((long) FUNCREF_TYPE << 32) | functionIndex;
                    break;
                case Instructions.GLOBAL_GET:
                    final int globalIndex = readGlobalIndex();
                    assertIntEqual(module.globalMutability(globalIndex), GlobalModifier.CONSTANT, Failure.CONSTANT_EXPRESSION_REQUIRED);
                    final byte valueType = module.globalValueType(globalIndex);
                    assertByteEqual(valueType, elemType, Failure.TYPE_MISMATCH);
                    functionIndices[index] = ((long) I32_TYPE << 32) | globalIndex;
                    break;
                default:
                    throw WasmException.format(Failure.ILLEGAL_OPCODE, "Illegal opcode for constant expression: 0x%02X", opcode);
            }
            readEnd();
        }
        return functionIndices;
    }

    private void readElementSection(BytecodeGen bytecode) {
        int elemSegmentCount = readLength();
        module.limits().checkElementSegmentCount(elemSegmentCount);
        for (int elemSegmentIndex = 0; elemSegmentIndex != elemSegmentCount; elemSegmentIndex++) {
            assertTrue(!isEOF(), Failure.LENGTH_OUT_OF_BOUNDS);
            int mode;
            final int currentOffsetAddress;
            final int currentOffsetGlobalIndex;
            final long[] elements;
            final int tableIndex;
            final byte elemType;
            if (bulkMemoryAndRefTypes) {
                final int sectionType = readUnsignedInt32();
                mode = sectionType & 0b001;
                final boolean useTableIndex = (sectionType & 0b010) != 0;
                final boolean useExpressions = (sectionType & 0b100) != 0;
                final boolean useType = (sectionType & 0b011) != 0;
                if (mode == SegmentMode.ACTIVE) {
                    if (useTableIndex) {
                        tableIndex = readTableIndex();
                    } else {
                        tableIndex = 0;
                    }
                    readOffsetExpression(multiResult);
                    currentOffsetAddress = multiResult[0];
                    currentOffsetGlobalIndex = multiResult[1];
                } else {
                    mode = useTableIndex ? SegmentMode.DECLARATIVE : SegmentMode.PASSIVE;
                    tableIndex = 0;
                    currentOffsetAddress = -1;
                    currentOffsetGlobalIndex = -1;
                }
                if (useExpressions) {
                    if (useType) {
                        elemType = readRefType();
                    } else {
                        elemType = FUNCREF_TYPE;
                    }
                    elements = readElemExpressions(elemType);
                } else {
                    if (useType) {
                        checkElemKind();
                    }
                    elemType = FUNCREF_TYPE;
                    elements = readFunctionIndices();
                }
            } else {
                mode = SegmentMode.ACTIVE;
                tableIndex = readTableIndex();
                readOffsetExpression(multiResult);
                currentOffsetAddress = multiResult[0];
                currentOffsetGlobalIndex = multiResult[1];
                elements = readFunctionIndices();
                elemType = FUNCREF_TYPE;
            }

            // Copy the contents, or schedule a linker task for this.
            final int currentElemSegmentId = elemSegmentIndex;
            final int elementCount = elements.length;
            final int headerOffset = bytecode.location();
            final int bytecodeOffset = bytecode.addElemHeader(mode, elementCount, elemType, tableIndex, currentOffsetGlobalIndex, currentOffsetAddress);
            module.setElemInstance(currentElemSegmentId, headerOffset, elemType);
            if (mode == SegmentMode.ACTIVE) {
                assertTrue(module.checkTableIndex(tableIndex), Failure.UNKNOWN_TABLE);
                module.addLinkAction(((context, instance) -> context.linker().resolveElemSegment(context, instance, tableIndex, currentElemSegmentId, currentOffsetAddress,
                                currentOffsetGlobalIndex, bytecodeOffset, elementCount)));
            } else if (mode == SegmentMode.PASSIVE) {
                module.addLinkAction(((context, instance) -> context.linker().resolvePassiveElemSegment(context, instance, currentElemSegmentId, bytecodeOffset, elementCount)));
            }
            for (long element : elements) {
                final int initType = (int) (element >> 32);
                switch (initType) {
                    case NULL_TYPE:
                        bytecode.addElemNull();
                        break;
                    case FUNCREF_TYPE:
                        final int functionIndex = (int) element;
                        bytecode.addElemFunctionIndex(functionIndex);
                        break;
                    case I32_TYPE:
                        final int globalIndex = (int) element;
                        bytecode.addElemGlobalIndex(globalIndex);
                        break;
                }
            }
        }
    }

    private void readEnd() {
        final byte instruction = read1();
        assertByteEqual(instruction, (byte) Instructions.END, Failure.TYPE_MISMATCH);
    }

    private void readStartSection() {
        int startFunctionIndex = readDeclaredFunctionIndex();
        module.symbolTable().setStartFunction(startFunctionIndex);
    }

    private void readExportSection() {
        int exportsCount = readLength();

        module.limits().checkExportCount(exportsCount);
        for (int exportIndex = 0; exportIndex != exportsCount; ++exportIndex) {
            assertTrue(!isEOF(), Failure.LENGTH_OUT_OF_BOUNDS);
            String exportName = readName();
            byte exportType = readExportType();
            switch (exportType) {
                case ExportIdentifier.FUNCTION: {
                    int functionIndex = readDeclaredFunctionIndex();
                    module.symbolTable().exportFunction(functionIndex, exportName);
                    module.addFunctionReference(functionIndex);
                    break;
                }
                case ExportIdentifier.TABLE: {
                    final int tableIndex = readTableIndex();
                    module.symbolTable().exportTable(tableIndex, exportName);
                    break;
                }
                case ExportIdentifier.MEMORY: {
                    readMemoryIndex();
                    module.symbolTable().exportMemory(exportName);
                    break;
                }
                case ExportIdentifier.GLOBAL: {
                    int index = readGlobalIndex();
                    module.symbolTable().exportGlobal(exportName, index);
                    break;
                }
                default: {
                    fail(Failure.UNSPECIFIED_MALFORMED, String.format("Invalid export type identifier: 0x%02X", exportType));
                }
            }
        }
    }

    private void readGlobalSection() {
        final int globalCount = readLength();
        module.limits().checkGlobalCount(globalCount);
        final int startingGlobalIndex = module.symbolTable().numGlobals();
        for (int globalIndex = startingGlobalIndex; globalIndex != startingGlobalIndex + globalCount; globalIndex++) {
            assertTrue(!isEOF(), Failure.LENGTH_OUT_OF_BOUNDS);
            final byte type = readValueType(bulkMemoryAndRefTypes);
            // 0x00 means const, 0x01 means var
            final byte mutability = readMutability();
            long value = 0;
            int existingIndex = -1;
            final int instruction = read1() & 0xFF;
            boolean isInitialized;
            final boolean isFunctionOrNull;
            // Global initialization expressions must be constant expressions:
            // https://webassembly.github.io/spec/core/valid/instructions.html#constant-expressions
            switch (instruction) {
                case Instructions.I32_CONST:
                    assertByteEqual(type, I32_TYPE, Failure.TYPE_MISMATCH);
                    value = readSignedInt32();
                    isInitialized = true;
                    isFunctionOrNull = false;
                    break;
                case Instructions.I64_CONST:
                    assertByteEqual(type, I64_TYPE, Failure.TYPE_MISMATCH);
                    value = readSignedInt64();
                    isInitialized = true;
                    isFunctionOrNull = false;
                    break;
                case Instructions.F32_CONST:
                    assertByteEqual(type, F32_TYPE, Failure.TYPE_MISMATCH);
                    value = readFloatAsInt32();
                    isInitialized = true;
                    isFunctionOrNull = false;
                    break;
                case Instructions.F64_CONST:
                    assertByteEqual(type, F64_TYPE, Failure.TYPE_MISMATCH);
                    value = readFloatAsInt64();
                    isInitialized = true;
                    isFunctionOrNull = false;
                    break;
                case Instructions.REF_NULL:
                    checkBulkMemoryAndRefTypesSupport(instruction);
                    assertByteEqual(type, readRefType(), Failure.TYPE_MISMATCH);
                    assertTrue(WasmType.isReferenceType(type), Failure.TYPE_MISMATCH);
                    isInitialized = true;
                    isFunctionOrNull = true;
                    break;
                case Instructions.REF_FUNC:
                    checkBulkMemoryAndRefTypesSupport(instruction);
                    assertByteEqual(type, FUNCREF_TYPE, Failure.TYPE_MISMATCH);
                    final int functionIndex = readDeclaredFunctionIndex();
                    module.addFunctionReference(functionIndex);
                    value = functionIndex;
                    isInitialized = false;
                    isFunctionOrNull = true;
                    break;
                case Instructions.GLOBAL_GET:
                    existingIndex = readGlobalIndex();
                    assertUnsignedIntLess(existingIndex, module.symbolTable().importedGlobals().size(), Failure.UNKNOWN_GLOBAL);
                    assertByteEqual(type, module.symbolTable().globalValueType(existingIndex), Failure.TYPE_MISMATCH);
                    assertByteEqual(GlobalModifier.CONSTANT, module.globalMutability(existingIndex), Failure.CONSTANT_EXPRESSION_REQUIRED);
                    isInitialized = false;
                    isFunctionOrNull = false;
                    break;
                default:
                    throw WasmException.create(Failure.TYPE_MISMATCH);
            }
            readEnd();

            module.symbolTable().declareGlobal(globalIndex, type, mutability, isInitialized, isFunctionOrNull, existingIndex, value);
            final int currentGlobalIndex = globalIndex;
            final int currentExistingIndex = existingIndex;
            final long currentValue = value;
            final int currentFunctionIndex = (int) value;
            module.addLinkAction((context, instance) -> {
                final GlobalRegistry globals = context.globals();
                final int address = instance.globalAddress(currentGlobalIndex);
                if (isInitialized) {
                    if (isFunctionOrNull) {
                        // Only null is possible
                        globals.storeReference(address, WasmConstant.NULL);
                    } else {
                        globals.storeLong(address, currentValue);
                    }
                    context.linker().resolveGlobalInitialization(instance, currentGlobalIndex);
                } else {
                    if (currentExistingIndex != -1 && !instance.symbolTable().importedGlobals().containsKey(currentExistingIndex)) {
                        // The current WebAssembly spec says constant expressions can only refer to
                        // imported globals. We can easily remove this restriction in the future.
                        fail(Failure.UNSPECIFIED_MALFORMED, "The initializer for global " + currentGlobalIndex + " in module '" + instance.name() +
                                        "' refers to a non-imported global.");
                    }
                    if (isFunctionOrNull) {
                        // Has to be a function reference
                        context.linker().resolveGlobalFunctionInitialization(context, instance, currentGlobalIndex, currentFunctionIndex);
                    } else {
                        context.linker().resolveGlobalInitialization(context, instance, currentGlobalIndex, currentExistingIndex);
                    }
                }
            });
        }
    }

    private void readDataCountSection(int size) {
        if (size == 0) {
            module.setDataSegmentCount(0);
        } else {
            module.setDataSegmentCount(readUnsignedInt32());
        }
    }

    private void readDataSection(BytecodeGen bytecode) {
        final int dataSegmentCount = readLength();
        module.limits().checkDataSegmentCount(dataSegmentCount);
        if (bulkMemoryAndRefTypes && dataSegmentCount != 0) {
            module.checkDataSegmentCount(dataSegmentCount);
        }
        final int droppedDataInstanceOffset = bytecode.location();
        module.setDroppedDataInstanceOffset(droppedDataInstanceOffset);
        bytecode.add(Bytecode.UNREACHABLE);
        for (int dataSegmentIndex = 0; dataSegmentIndex != dataSegmentCount; ++dataSegmentIndex) {
            assertTrue(!isEOF(), Failure.LENGTH_OUT_OF_BOUNDS);
            final int mode;
            long offsetAddress;
            final int offsetGlobalIndex;
            if (bulkMemoryAndRefTypes) {
                final int sectionType = readUnsignedInt32();
                mode = sectionType & 0b01;
                final boolean useMemoryIndex = (sectionType & 0b10) != 0;
                if (useMemoryIndex) {
                    readMemoryIndex();
                }
                if (mode == SegmentMode.ACTIVE) {
                    if (module.memoryHasIndexType64()) {
                        readLongOffsetExpression(longMultiResult);
                        offsetAddress = longMultiResult[0];
                        offsetGlobalIndex = (int) longMultiResult[1];
                    } else {
                        readOffsetExpression(multiResult);
                        offsetAddress = multiResult[0];
                        offsetGlobalIndex = multiResult[1];
                    }
                } else {
                    offsetAddress = 0;
                    offsetGlobalIndex = 0;
                }
            } else {
                mode = SegmentMode.ACTIVE;
                readMemoryIndex();
                if (module.memoryHasIndexType64()) {
                    readLongOffsetExpression(longMultiResult);
                    offsetAddress = longMultiResult[0];
                    offsetGlobalIndex = (int) longMultiResult[1];
                } else {
                    readOffsetExpression(multiResult);
                    offsetAddress = multiResult[0];
                    offsetGlobalIndex = multiResult[1];
                }
            }

            final int byteLength = readLength();
            final int currentDataSegmentId = dataSegmentIndex;

            final int headerOffset = bytecode.location();
            if (mode == SegmentMode.ACTIVE) {
                assertTrue(module.memoryExists(), Failure.UNKNOWN_MEMORY);
                final long currentOffsetAddress = offsetAddress;
                bytecode.addDataHeader(byteLength, offsetGlobalIndex, currentOffsetAddress);
                final int bytecodeOffset = bytecode.location();
                module.setDataInstance(currentDataSegmentId, headerOffset);
                module.addLinkAction((context, instance) -> context.linker().resolveDataSegment(context, instance, currentDataSegmentId, currentOffsetAddress, offsetGlobalIndex, byteLength,
                                bytecodeOffset, droppedDataInstanceOffset));
            } else {
                bytecode.addDataHeader(mode, byteLength);
                final int bytecodeOffset = bytecode.location();
                bytecode.addDataRuntimeHeader(byteLength, unsafeMemory);
                module.setDataInstance(currentDataSegmentId, headerOffset);
                module.addLinkAction((context, instance) -> context.linker().resolvePassiveDataSegment(context, instance, currentDataSegmentId, bytecodeOffset, byteLength));
            }
            // Add the data section to the bytecode.
            for (int i = 0; i < byteLength; i++) {
                bytecode.addByte(read1());
            }
        }
    }

    private void readFunctionType() {
        int paramCount = readLength();
        long resultCountAndValue = peekUnsignedInt32AndLength(data, offset + paramCount);
        int resultCount = value(resultCountAndValue);
        resultCount = (resultCount == 0x40) ? 0 : resultCount;

        module.limits().checkParamCount(paramCount);
        module.limits().checkResultCount(resultCount, multiValue);
        int idx = module.symbolTable().allocateFunctionType(paramCount, resultCount, multiValue);
        readParameterList(idx, paramCount);
        offset += length(resultCountAndValue);
        readResultList(idx, resultCount);
    }

    private void readParameterList(int funcTypeIdx, int paramCount) {
        for (int paramIdx = 0; paramIdx != paramCount; ++paramIdx) {
            byte type = readValueType(bulkMemoryAndRefTypes);
            module.symbolTable().registerFunctionTypeParameterType(funcTypeIdx, paramIdx, type);
        }
    }

    private void readResultList(int funcTypeIdx, int resultCount) {
        for (int resultIdx = 0; resultIdx != resultCount; resultIdx++) {
            byte type = readValueType(bulkMemoryAndRefTypes);
            module.symbolTable().registerFunctionTypeResultType(funcTypeIdx, resultIdx, type);
        }
    }

    private boolean isEOF() {
        return offset == data.length;
    }

    private int readDeclaredFunctionIndex() {
        final int index = readUnsignedInt32();
        module.symbolTable().checkFunctionIndex(index);
        return index;
    }

    private int readTypeIndex() {
        final int result = readUnsignedInt32();
        assertUnsignedIntLess(result, module.symbolTable().typeCount(), Failure.UNKNOWN_TYPE);
        return result;
    }

    private int readFunctionIndex() {
        return readUnsignedInt32();
    }

    private int readTableIndex() {
        final int index = readUnsignedInt32();
        assertTrue(module.symbolTable().checkTableIndex(index), Failure.UNKNOWN_TABLE);
        return index;
    }

    private int readMemoryIndex() {
        return checkMemoryIndex(readUnsignedInt32());
    }

    private int checkMemoryIndex(int index) {
        assertTrue(module.symbolTable().memoryExists(), Failure.UNKNOWN_MEMORY);
        assertIntEqual(index, 0, Failure.UNKNOWN_MEMORY);
        return index;
    }

    private int readGlobalIndex() {
        final int index = readUnsignedInt32();
        assertUnsignedIntLess(index, module.symbolTable().numGlobals(), Failure.UNKNOWN_GLOBAL);
        return index;
    }

    private int readLocalIndex() {
        return readUnsignedInt32();
    }

    private int readTargetOffset() {
        return readUnsignedInt32();
    }

    private byte readExportType() {
        return read1();
    }

    private byte readImportType() {
        return read1();
    }

    private byte readRefType() {
        final byte refType = read1();
        switch (refType) {
            case FUNCREF_TYPE:
            case EXTERNREF_TYPE:
                break;
            default:
                fail(Failure.MALFORMED_REFERENCE_TYPE, "Unexpected reference type");
                break;
        }
        return refType;
    }

    private void readTableLimits(int[] out) {
        readLimits(out, MAX_TABLE_DECLARATION_SIZE);
        assertUnsignedIntLessOrEqual(out[0], out[1], Failure.LIMIT_MINIMUM_GREATER_THAN_MAXIMUM);
    }

    private boolean readMemoryLimits(long[] out) {
        final boolean is64Bit = readLongLimits(out, MAX_MEMORY_DECLARATION_SIZE, MAX_MEMORY_64_DECLARATION_SIZE);
        if (is64Bit) {
            assertUnsignedLongLessOrEqual(out[0], MAX_MEMORY_64_DECLARATION_SIZE, Failure.MEMORY_64_SIZE_LIMIT_EXCEEDED);
            assertUnsignedLongLessOrEqual(out[1], MAX_MEMORY_64_DECLARATION_SIZE, Failure.MEMORY_64_SIZE_LIMIT_EXCEEDED);
            assertUnsignedLongLessOrEqual(out[0], out[1], Failure.LIMIT_MINIMUM_GREATER_THAN_MAXIMUM);
        } else {
            assertUnsignedIntLessOrEqual((int) out[0], MAX_MEMORY_DECLARATION_SIZE, Failure.MEMORY_SIZE_LIMIT_EXCEEDED);
            assertUnsignedIntLessOrEqual((int) out[1], MAX_MEMORY_DECLARATION_SIZE, Failure.MEMORY_SIZE_LIMIT_EXCEEDED);
            assertUnsignedIntLessOrEqual((int) out[0], (int) out[1], Failure.LIMIT_MINIMUM_GREATER_THAN_MAXIMUM);
        }
        return is64Bit;
    }

    private void readLimits(int[] out, int max) {
        final byte limitsPrefix = readLimitsPrefix();
        switch (limitsPrefix) {
            case LimitsPrefix.NO_MAX: {
                out[0] = readUnsignedInt32();
                out[1] = max;
                break;
            }
            case LimitsPrefix.WITH_MAX: {
                out[0] = readUnsignedInt32();
                out[1] = readUnsignedInt32();
                break;
            }
            default:
                if (limitsPrefix < 0) {
                    fail(Failure.INTEGER_REPRESENTATION_TOO_LONG, String.format("Invalid limits prefix (expected 0x00 or 0x01, got 0x%02X", limitsPrefix));
                } else {
                    fail(Failure.INTEGER_TOO_LARGE, String.format("Invalid limits prefix (expected 0x00 or 0x01, got 0x%02X", limitsPrefix));
                }
        }
    }

    private boolean readLongLimits(long[] out, int max32Bit, long max64Bit) {
        final byte limitsPrefix = readLimitsPrefix();
        switch (limitsPrefix) {
            case 0x00: {
                out[0] = readUnsignedInt32();
                out[1] = max32Bit;
                return false;
            }
            case 0x01: {
                out[0] = readUnsignedInt32();
                out[1] = readUnsignedInt32();
                return false;
            }
            case 0x04: {
                out[0] = readUnsignedInt64();
                out[1] = max64Bit;
                return true;
            }
            case 0x05: {
                out[0] = readUnsignedInt64();
                out[1] = readUnsignedInt64();
                return true;
            }
            default:
                if (limitsPrefix < 0) {
                    fail(Failure.INTEGER_REPRESENTATION_TOO_LONG, String.format("Invalid limits prefix (expected 0x00, 0x01, 0x04, or 0x05, got 0x%02X", limitsPrefix));
                } else {
                    fail(Failure.INTEGER_TOO_LARGE, String.format("Invalid limits prefix (expected 0x00, 0x01, 0x04, or 0x05, got 0x%02X", limitsPrefix));
                }
        }
        return false;
    }

    private byte readLimitsPrefix() {
        return read1();
    }

    private String readName() {
        int nameLength = readLength();
        assertUnsignedIntLessOrEqual(offset + nameLength, data.length, Failure.LENGTH_OUT_OF_BOUNDS);

        // Decode and verify UTF-8 encoding of the name
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        decoder.onMalformedInput(CodingErrorAction.REPORT);
        decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer result;
        try {
            result = decoder.decode(ByteBuffer.wrap(data, offset, nameLength));
        } catch (CharacterCodingException ex) {
            throw WasmException.format(Failure.MALFORMED_UTF8, "Invalid UTF-8 encoding of the name at: %d", offset);
        }
        offset += nameLength;
        return result.toString();
    }

    protected int readLength() {
        final int value = readUnsignedInt32();
        assertUnsignedIntLessOrEqual(value, data.length, Failure.LENGTH_OUT_OF_BOUNDS);
        return value;
    }

    protected int readAlignHint(int n) {
        final int value = readUnsignedInt32();
        assertUnsignedIntLessOrEqual(1 << value, n / 8, Failure.ALIGNMENT_LARGER_THAN_NATURAL);
        return value;
    }

    protected int readUnsignedInt32() {
        final long valueLength = peekUnsignedInt32AndLength(data, offset);
        offset += length(valueLength);
        return value(valueLength);
    }

    protected int readSignedInt32() {
        final long valueLength = peekSignedInt32AndLength(data, offset);
        offset += length(valueLength);
        return value(valueLength);
    }

    protected long readUnsignedInt64() {
        final long value = peekUnsignedInt64(data, offset, true);
        final byte length = peekLeb128Length(data, offset);
        offset += length;
        return value;
    }

    private long readSignedInt64() {
        final long value = peekSignedInt64(data, offset, true);
        final byte length = peekLeb128Length(data, offset);
        offset += length;
        return value;
    }
}
