package mxcompiler.ir;

import java.util.*;

import mxcompiler.asm.x86_64RegisterSet;
import mxcompiler.ast.statement.StmtNode;
import mxcompiler.ir.instruction.BasicBlock;
import mxcompiler.ir.instruction.Function;
import mxcompiler.ir.register.*;
import static mxcompiler.utils.Tool.*;


public class Root {
    public boolean hasDivShiftInst = false;

    /** pregs -> used for stack slot (defined once) */
    public PhysicalRegister preg0, preg1;

    /** add builtIn func here */
    public Root() {
        initBuiltInFunc();
    }

    // region builtinFun
    private Map<String, Function> builtInFuncs = new HashMap<>();

    private void addFunc(String name, String label) {
        Function func;
        func = new Function(name, label);
        func.usedPhysicalGeneralRegs.addAll(x86_64RegisterSet.generalRegs);
        builtInFuncs.put(func.getName(), func);
    }

    public void initBuiltInFunc() {
        addFunc(PRINT_KEY, "_Z5printPc");
        addFunc(PRINTLN_KEY, "_Z7printlnPc");
        addFunc(PRINTINT_KEY, "_Z8printInti");
        addFunc(PRINTLNINT_KEY, "_Z10printlnInti");
        addFunc(GETSTRING_KEY, "_Z9getStringv");
        addFunc(GETINT_KEY, "_Z6getIntv");
        addFunc(TOSTRING_KEY, "_Z8toStringi");

        addFunc(SUBSTRING_KEY, "_Z27__member___string_substringPcii");
        addFunc(PARSEINT_KEY, "_Z26__member___string_parseIntPc");
        addFunc(ORD_KEY, "_Z21__member___string_ordPci");

        addFunc(STRING_CONCAT_KEY, "__builtin_string_concat");
        addFunc(STRING_EQUAL_KEY, "__builtin_string_equal");
        addFunc(STRING_INEQUAL_KEY, "__builtin_string_inequal");
        addFunc(STRING_LESS_KEY, "__builtin_string_less");
        addFunc(STRING_LESS_EQUAL_KEY, "__builtin_string_less_equal");
    }

    public Map<String, Function> getBuiltInFunc() {
        return builtInFuncs;
    }

    public Function getBuiltInFunc(String name) {
        return builtInFuncs.get(name);
    }
    // endregion

    // region funcs
    private Map<String, Function> funcs = new HashMap<>();

    /**
     * attention: can not get {@code BuiltInFunc}
     */
    public Map<String, Function> getFunc() {
        return funcs;
    }

    // or called addFunc
    public void putFunc(Function func) {
        funcs.put(func.getName(), func);
    }

    public void delFunc(String name) {
        funcs.remove(name);
    }

    public Function getFunc(String name) {
        return funcs.get(name);
    }
    // endregion

    // region static
    private Map<String, StaticString> staticStrs = new HashMap<>();
    private List<StaticData> staticDataList = new ArrayList<>();

    public Map<String, StaticString> getStaticStr() {
        return staticStrs;
    }

    public void putStaticStr(StaticString str) {
        staticStrs.put(str.getValue(), str);
    }

    public StaticString getStaticStr(String name) {
        return staticStrs.get(name);
    }

    public void putStaticData(StaticData data) {
        staticDataList.add(data);
    }

    public List<StaticData> getStaticDataList() {
        return staticDataList;
    }
    // endregion

    public Map<StmtNode, ForRecord> forRecMap = new HashMap<>();

    public static class ForRecord {
        public BasicBlock cond, incr, body, after;
        public boolean processed = false;

        public ForRecord(BasicBlock cond, BasicBlock incr, BasicBlock body, BasicBlock after) {
            this.cond = cond;
            this.incr = incr;
            this.body = body;
            this.after = after;
        }
    }

    /**
     * @Usage update recursiveCallee
     * @Usage Do {@link #Function.updateCalleeSet()} first
     */
    public void updateCalleeSet() {
        for (Function irFunc : funcs.values())
            irFunc.updateCalleeSet();

        Set<Function> recursiveCalleeSet = new HashSet<>();
        for (Function irFunc : funcs.values())
            irFunc.recursiveCalleeSet.clear();

        boolean changed;
        do {
            changed = false;
            for (Function irFunc : funcs.values()) {
                recursiveCalleeSet.clear();
                recursiveCalleeSet.addAll(irFunc.calleeSet);

                for (Function calleeFunction : irFunc.calleeSet)
                    recursiveCalleeSet.addAll(calleeFunction.recursiveCalleeSet);

                if (!recursiveCalleeSet.equals(irFunc.recursiveCalleeSet)) {
                    irFunc.recursiveCalleeSet.clear();
                    irFunc.recursiveCalleeSet.addAll(recursiveCalleeSet);
                    changed = true;
                }
            }
        } while (changed);
    }

    public void accept(IRVisitor visitor) {
        visitor.visit(this);
    }
}