package com.github.zxh.classpy.wasm.sections;

import com.github.zxh.classpy.common.ParseException;
import com.github.zxh.classpy.wasm.Vector;
import com.github.zxh.classpy.wasm.WasmBinPart;
import com.github.zxh.classpy.wasm.WasmBinFile;
import com.github.zxh.classpy.wasm.WasmBinReader;
import com.github.zxh.classpy.wasm.types.FuncType;
import com.github.zxh.classpy.wasm.types.Limits;
import com.github.zxh.classpy.wasm.types.TableType;
import com.github.zxh.classpy.wasm.values.Byte;
import com.github.zxh.classpy.wasm.values.Index;

import java.util.List;

public class Section extends WasmBinPart {

    private int id;

    public int getID() {
        return id;
    }

    @Override
    protected void readContent(WasmBinReader reader) {
        id = readID(reader);
        int size = readU32(reader, "size");
        readContents(reader, id, size);
    }

    private int readID(WasmBinReader reader) {
        Byte id = new Byte();
        add("id", id);
        id.read(reader);
        id.setDesc(Integer.toString(id.getValue()));
        return id.getValue();
    }

    private void readContents(WasmBinReader reader,
                              int id, int size) {
        switch (id) {
            case 0 -> readCustomSection(reader, size);
            case 1 -> {
                setName("type section");
                readVector(reader, "types", FuncType::new);
            }
            case 2 -> {
                setName("import section");
                readVector(reader, "imports", Import::new);
            }
            case 3 -> {
                setName("function section");
                readVector(reader, "functions", Index::new);
            }
            case 4 -> {
                setName("table section");
                readVector(reader, "tables", TableType::new);
            }
            case 5 -> {
                setName("memory section");
                readVector(reader, "memories", Limits::new);
            }
            case 6 -> {
                setName("global section");
                readVector(reader, "globals", Global::new);
            }
            case 7 -> {
                setName("export section");
                readVector(reader, "exports", Export::new);
            }
            case 8 -> {
                setName("start section");
                readIndex(reader, "funcidx");
            }
            case 9 -> {
                setName("element section");
                readVector(reader, "elements", Element::new);
            }
            case 10 -> {
                setName("code section");
                readVector(reader, "codes", Code::new);
            }
            case 11 -> {
                setName("data section");
                readVector(reader, "datas", Data::new);
            }
            default -> throw new ParseException("Invalid section id: " + id);
        }
    }

    private void readCustomSection(WasmBinReader reader, int size) {
        int pos1 = reader.getPosition();
        String name = readName(reader, "name");
        setName("custom section: " + name);

        if (name.equals("name")) {
            readNameData(reader);
        } else if (name.equals("dylink")) {
            readDyLinkData(reader);
        } else {
            int pos2 = reader.getPosition();
            size -= (pos2 - pos1);
            if (size > 0) {
                readBytes(reader, "contents", size);
            }
        }
    }

    private void readNameData(WasmBinReader reader) {
        while (reader.remaining() > 0) {
            int subID = readByte(reader, "subID");
            int size = readU32(reader, "size");
            if (subID == 1) {
                readVector(reader, "function names", NameAssoc::new);
            } else {
                readBytes(reader, "contents", size);
            }
        }
    }

    private void readDyLinkData(WasmBinReader reader) {
        readU32(reader, "memorySize");
        readU32(reader, "memoryAlignment");
        readU32(reader, "tableSize");
        readU32(reader, "tableAlignment");
    }

    @Override
    protected void postRead(WasmBinFile wasm) {
        if (id == 0) {
            postReadCustomSec(wasm);
        } else if (id == 1) {
            postReadTypes(wasm);
        } else if (id == 2) {
            postReadImports(wasm);
        } else if (id == 3) {
            postReadFuncs(wasm);
        } else if (id == 6) {
            postReadGlobals(wasm);
        } else if (id == 10) {
            postReadCodes(wasm);
        }
    }

    private void postReadTypes(WasmBinFile wasm) {
        int typeIdx = 0;
        for (FuncType ft : wasm.getFuncTypes()) {
            ft.setName("#" + (typeIdx++));
        }
    }

    private void postReadImports(WasmBinFile wasm) {
        int funcIdx = 0;
        int globalIdx = 0;
        for (Import imp : wasm.getImports()) {
            if (imp.isFunc()) {
                imp.setName("func#" + (funcIdx++));
            } else if (imp.isGlobal()) {
                imp.setName("global#" + (globalIdx++));
            }
        }
        for (Global glb : wasm.getGlobals()) {
            glb.setName("global#" + (globalIdx++));
        }
    }

    private void postReadFuncs(WasmBinFile wasm) {
        int idx = wasm.getImportedFuncs().size();
        for (Index sigIdx : wasm.getFuncs()) {
            sigIdx.setName("func#" + (idx++));
            sigIdx.setDesc("sig=" + sigIdx.getValue());
        }
    }

    private void postReadGlobals(WasmBinFile wasm) {
        int idx = wasm.getImportedGlobals().size();
        for (Global glb : wasm.getGlobals()) {
            glb.setName("global#" + (idx++));
        }
    }

    private void postReadCodes(WasmBinFile wasm) {
        List<Code> codes = wasm.getCodes();
        int importedFuncCount = wasm.getImportedFuncs().size();
        for (int i = 0; i < codes.size(); i++) {
            codes.get(i).setName("func#" + (importedFuncCount + i));
        }
        for (Export export : wasm.getExports()) {
            if (export.getFuncIdx() >= 0) {
                int idx = export.getFuncIdx() - importedFuncCount;
                if (idx < codes.size()) {
                    codes.get(idx).setDesc(export.getDesc());
                }
            }
        }
    }

    private void postReadCustomSec(WasmBinFile wasm) {
        List<Index> funcIndexes = wasm.getFuncs();

        Vector funcNames = (Vector) get("function names");
        if (funcNames != null) {
            funcNames.getParts().stream()
                    .filter(p -> p instanceof NameAssoc)
                    .map(p -> (NameAssoc) p)
                    .filter(funcName -> funcName.idx < funcIndexes.size())
                    .forEach(funcName -> {
                        Index funcIdx = funcIndexes.get(funcName.idx);
                        funcIdx.setDesc(funcIdx.getDesc() + ", name=" + funcName.name);
                    });
        }
    }


    private static class NameAssoc extends WasmBinPart {

        private int idx;
        private String name;

        @Override
        protected void readContent(WasmBinReader reader) {
            idx = readIndex(reader, "idx");
            name = readName(reader, "name");
            setName("#" + idx);
            setDesc(name + "()");
        }

    }

}
