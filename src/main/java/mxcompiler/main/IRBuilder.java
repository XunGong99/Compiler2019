package mxcompiler.main;

import java.util.*;

import mxcompiler.utils.Tool;
import mxcompiler.utils.entity.*;
import mxcompiler.utils.scope.*;
import mxcompiler.utils.type.*;

import mxcompiler.error.*;

import mxcompiler.ast.*;
import mxcompiler.ast.statement.*;
import mxcompiler.ast.declaration.*;
import mxcompiler.ast.expression.*;
import mxcompiler.ast.expression.literal.*;
import mxcompiler.ast.expression.lhs.*;
import mxcompiler.ast.expression.unary.*;
import mxcompiler.ir.*;
import mxcompiler.ir.instruction.*;
import mxcompiler.ir.register.*;


/**
 * The whole IR is built under Control Flow Graph Considered no SSA, no tree
 */
public class IRBuilder extends Visitor {

    public final Root root = new Root();

    private ToplevelScope toplevelScope;
    private List<GlobalVarInit> globalInitList = new ArrayList<>();

    private ClassEntity curClass = null;
    private Function curFunc = null;
    private BasicBlock curBB = null;
    private BasicBlock curLoopStepBB, curLoopAfterBB; // cur
    private Scope curScope;

    /**
     * selfDecrIncr -> {@code want = true, if(needMemOp =
     * isMemoryAccess(expr))}
     * <p>
     * assignNode -> {@code wantAddr = needMemOp =
     * isMemoryAccess(node.getLhs())}
     */
    private boolean curWantAddr = false;

    private boolean isFuncArgDecl = false;

    /**
     * only when {@link #visit(AssignExprNode) }, {@code assignLhs = true;}
     */
    private boolean assignLhs = false;

    /**
     * only when {@link #visit(IdentifierExprNode)}
     */
    private boolean uselessStatic = false;

    // region visit decl

    /**
     * mainfunction
     */
    public void visit(ASTNode node) {
        toplevelScope = node.getScope();
        curScope = toplevelScope;

        // add funcs
        for (DeclNode decl : node.getDecl()) {
            if (decl instanceof FuncDeclNode) {
                Entity entity = curScope.get(decl.getName());
                Function newIRFunc = new Function((FuncEntity) entity);
                root.putFunc(newIRFunc);
            } else if (decl instanceof ClassDeclNode) {
                ClassEntity entity = (ClassEntity) curScope.get(decl.getName());
                curScope = entity.getScope();
                curClass = entity;
                for (FuncDeclNode func : ((ClassDeclNode) decl).getFunc()) {
                    Entity funcEntity = curScope.get(curClass.getDomain() + func.getName());
                    Function newIRFunc = new Function((FuncEntity) funcEntity);
                    root.putFunc(newIRFunc);
                }
                curClass = null;
                curScope = curScope.getParent();
            }
        }

        // add global init list
        for (DeclNode decl : node.getDecl())
            if (decl instanceof VarDeclNode)
                visit(decl);
        // FIX: add global init in resolver
        visit(makeGlobalVarInit()); // has order, have to do at first

        for (DeclNode decl : node.getDecl())
            if (!(decl instanceof VarDeclNode))
                visit(decl);

        for (Function irFunc : root.getFunc().values())
            irFunc.updateCalleeSet();
        root.updateCalleeSet();

        TwoRegOpTransformer();
    }

    public void visit(VarDeclNode node) {
        VarEntity entity = (VarEntity) curScope.get(node.getName());
        if (entity.unUsed)
            return;

        if (curScope == toplevelScope) { // global var
            StaticData data = new StaticVar(node.getName(), RegValue.RegSize);
            root.putStaticData(data); // has order
            entity.register = data;

            if (node.getInit() != null) {
                GlobalVarInit init = new GlobalVarInit(node.getName(), node.getInit());
                globalInitList.add(init); // has order
            }

            if (node.getInit() != null) {
                GlobalVarInit init = new GlobalVarInit(node.getName(), node.getInit());
                globalInitList.add(init);
            }
        } else { // other var
            VirtualRegister vreg = new VirtualRegister(node.getName());
            entity.register = vreg;

            if (isFuncArgDecl)
                curFunc.argVReg.add(vreg);

            if (node.getInit() == null) {
                if (!isFuncArgDecl) {
                    // curBB.addInst(new Move(curBB, vreg, new IntImm(0)));
                    System.out.println("Node init with args");
                }
            } else {
                // a init var; not literal
                if (node.getInit().getType() instanceof BoolType
                        && !(node.getInit() instanceof BoolLiteralExprNode)) {
                    node.getInit().setThen(new BasicBlock(curFunc, null));
                    node.getInit().setElse(new BasicBlock(curFunc, null));
                }

                visit(node.getInit());
                dealAssign(vreg, 0, node.getInit(), node.getInit().getType().getRegSize(),
                        false);
            }
        }
    }

    public void visit(ClassDeclNode node) {
        if (curScope == toplevelScope)
            throw new CompileError("ClassDecl Have to be toplevelScope in IRBuilder");
        curClass = (ClassEntity) toplevelScope.get(node.getName());

        //
        for (FuncDeclNode decl : node.getFunc())
            visit(decl);

        curClass = null;
    }

    public void visit(FuncDeclNode node) {
        String name = (curClass == null) ? node.getName()
                : curClass.getDomain() + node.getName();
        curFunc = root.getFunc(name);
        if (curFunc == null)
            throw new CompileError("Can not find funcdecl in ir:" + name);
        curBB = curFunc.initStart();

        Scope tmp = curScope;
        curScope = node.getBody().getScope(); // func-block-scope
        // region params

        if (curClass != null) { // deal this-param
            VarEntity entity = (VarEntity) curScope.get(Scope.BuiltIn.THIS.toString());

            VirtualRegister vreg = new VirtualRegister(Scope.BuiltIn.THIS.toString());
            entity.register = vreg;
            curFunc.argVReg.add(vreg);
        }
        isFuncArgDecl = true; // deal other param
        for (VarDeclNode argDecl : node.getVar())
            visit(argDecl);
        isFuncArgDecl = false;
        // endregion
        curScope = tmp;

        // region add inst
        // global init func
        if (node.getName().equals("main"))
            curBB.addInst(
                    new Funcall(curBB, root.getFunc(INIT_FUNC_NAME), new ArrayList<>(), null));

        visit(node.getBody());

        // final-return (may have other return in other blocks)
        if (!curBB.hasJump()) { // return type
            if (node.getReturnType().getType() instanceof NullType
                    || node.getReturnType().getType() instanceof VoidType)
                curBB.setJump(new Return(curBB, null));
            else // has return value fix: todo: why need intImm ??
                curBB.setJump(new Return(curBB, new IntImm(0)));
        }

        // merge multiple return instructions to a single end basic
        // block
        if (curFunc.returns.size() > 1) {
            BasicBlock mergeEnd = new BasicBlock(curFunc, curFunc.getName() + "_end");

            // set return value
            VirtualRegister retReg;
            if (node.getReturnType().getType() instanceof NullType
                    || node.getReturnType().getType() instanceof VoidType)
                retReg = null;
            else
                retReg = new VirtualRegister("return_value");

            // transfer return block to jump block
            List<Return> retList = new ArrayList<>(curFunc.returns);
            for (Return ret : retList) {
                BasicBlock beforeRet = ret.getParent();
                beforeRet.delInst(ret);

                if (ret.getReturnVal() != null) { // add move to reg
                    Move tmpInst = new Move(beforeRet, retReg, ret.getReturnVal());
                    beforeRet.addInst(tmpInst);
                }

                beforeRet.setJump(new Jump(beforeRet, mergeEnd));
            }

            mergeEnd.setJump(new Return(mergeEnd, retReg));
            curFunc.end = mergeEnd;
        } else
            curFunc.end = curFunc.returns.get(0).getParent();
        // endregion

        curBB = null;
        curFunc = null;
    }
    // endregion

    // region ----------------- global var func-init
    // ------------------
    // NOTE: make global var init as a function
    private final String INIT_FUNC_NAME = "_init_func";

    private FuncDeclNode makeGlobalVarInit() {
        if (toplevelScope != curScope)
            throw new CompileError("Error when global var init");

        // exprs may used in global init
        List<Node> stmts = new ArrayList<>();
        for (GlobalVarInit init : globalInitList) {
            VarEntity entity = (VarEntity) toplevelScope.get(init.getName());

            IdentifierExprNode lhs = new IdentifierExprNode(init.getName(), null);
            lhs.entity = entity;

            AssignExprNode assignExpr = new AssignExprNode(lhs, init.getExpr(), null);
            stmts.add(new ExprStmtNode(assignExpr, null));
        }

        BlockStmtNode body = new BlockStmtNode(stmts, null);
        body.setScope(new LocalScope(toplevelScope));

        TypeNode returnType = new TypeNode(new VoidType(), null);
        List<VarDeclNode> params = new ArrayList<>();
        FuncDeclNode funcNode = new FuncDeclNode(INIT_FUNC_NAME, returnType, params, body,
                null);
        FuncEntity funcEntity = new FuncEntity(funcNode);
        toplevelScope.put(INIT_FUNC_NAME, funcEntity);

        Function newIRFunc = new Function(funcEntity);
        root.putFunc(newIRFunc);
        return funcNode;
    }
    // endregion

    // region stmt

    /**
     * set basicblock inside the stmt not block-stmt set new block outside block not
     * the block-stmt
     */
    public void visit(BlockStmtNode node) {
        curScope = node.getScope();

        for (Node stmt : node.getAll()) {
            visit(stmt); // vardecl or stmt
            if (curBB.hasJump())
                break;
        }

        curScope = curScope.getParent();
    }

    public void visit(BreakStmtNode node) {
        curBB.setJump(new Jump(curBB, curLoopAfterBB));
    }

    public void visit(ContinueStmtNode node) {
        curBB.setJump(new Jump(curBB, curLoopStepBB));
    }

    public void visit(ForStmtNode node) {
        BasicBlock condBB, incrBB, bodyBB, afterBB;
        ExprNode cond = node.getCond(), incr = node.getIncr();

        bodyBB = new BasicBlock(curFunc, "for_body");
        condBB = (cond != null) ? new BasicBlock(curFunc, "for_cond") : bodyBB;
        // FIX::?? why and why not bodyBB?
        incrBB = (incr != null) ? new BasicBlock(curFunc, "for_incr") : condBB;
        afterBB = new BasicBlock(curFunc, "for_after");
        condBB.forNode = incrBB.forNode = bodyBB.forNode = afterBB.forNode = node;

        root.forRecMap.put(node, new Root.ForRecord(condBB, incrBB, bodyBB, afterBB));

        // TODO: change into stack
        BasicBlock tmpLoopStepBB = curLoopStepBB;
        BasicBlock tmpLoopAfterBB = curLoopAfterBB;
        curLoopStepBB = incrBB;
        curLoopAfterBB = afterBB;
        // region deal loop

        if (node.getInit() != null) // can not put in body-BB
            visit(node.getInit());

        curBB.setJump(new Jump(curBB, condBB));

        if (cond != null) {
            curBB = condBB;
            cond.setThen(bodyBB);
            cond.setElse(afterBB);
            visit(cond);

            if (cond instanceof BoolLiteralExprNode)
                curBB.setJump(new CJump(curBB, cond.regValue, cond.getThen(), cond.getElse()));
            else
                throw new CompileError("For condition without bool");
        }

        if (incr != null) { // can not put in body-BB
            curBB = incrBB;
            visit(incr);
            curBB.setJump(new Jump(curBB, condBB));
        }

        curBB = bodyBB;
        if (node.getBody() != null)
            visit(node.getBody());
        if (!curBB.hasJump())
            curBB.setJump(new Jump(curBB, incrBB));
        curBB = afterBB;

        // endregion
        curLoopStepBB = tmpLoopStepBB;
        curLoopAfterBB = tmpLoopAfterBB;
    }

    public void visit(WhileStmtNode node) {
        BasicBlock condBB, bodyBB, afterBB;
        condBB = new BasicBlock(curFunc, "while_cond");
        bodyBB = new BasicBlock(curFunc, "while_body");
        afterBB = new BasicBlock(curFunc, "while_after");

        BasicBlock tmpLoopStepBB = curLoopStepBB;
        BasicBlock tmpLoopAfterBB = curLoopAfterBB;
        curLoopStepBB = condBB;
        curLoopAfterBB = afterBB;

        curBB.setJump(new Jump(curBB, condBB));
        curBB = condBB;
        ExprNode cond = node.getCond();
        cond.setThen(bodyBB);
        cond.setElse(afterBB);
        visit(cond);
        if (cond instanceof BoolLiteralExprNode)
            curBB.setJump(new CJump(curBB, cond.regValue, cond.getThen(), cond.getElse()));

        curBB = bodyBB;
        visit(node.getBody());
        if (!curBB.hasJump())
            curBB.setJump(new Jump(curBB, condBB));

        curLoopStepBB = tmpLoopStepBB;
        curLoopAfterBB = tmpLoopAfterBB;

        curBB = afterBB;
    }

    public void visit(IfStmtNode node) {
        BasicBlock thenBB, elseBB, afterBB;
        thenBB = new BasicBlock(curFunc, "if_then");
        elseBB = (node.getElse() == null) ? null : new BasicBlock(curFunc, "if_else");
        afterBB = new BasicBlock(curFunc, "if_after");

        // cond
        ExprNode cond = node.getCond();
        cond.setThen(thenBB);
        cond.setElse((node.getElse() == null) ? afterBB : elseBB);
        visit(cond);
        if (cond instanceof BoolLiteralExprNode)
            curBB.setJump(new CJump(curBB, cond.regValue, cond.getElse(), cond.getThen()));

        // then
        curBB = thenBB;
        visit(node.getThen());
        if (!curBB.hasJump())
            curBB.setJump(new Jump(curBB, afterBB));

        // else
        if (node.getElse() != null) {
            curBB = elseBB;
            visit(node.getElse());
            if (!curBB.hasJump())
                curBB.setJump(new Jump(curBB, afterBB));
        }

        curBB = afterBB;
    }

    public void visit(ReturnStmtNode node) {
        Type type = curFunc.getEntity().getReturnType();

        if (type instanceof NullType || type instanceof VoidType) {
            curBB.setJump(new Return(curBB, null));

        } else {
            ExprNode expr = node.getExpr();
            if (type instanceof BoolType && !(expr instanceof BoolLiteralExprNode)) {
                expr.setThen(new BasicBlock(curFunc, null));
                expr.setElse(new BasicBlock(curFunc, null));
                visit(expr);

                VirtualRegister vreg = new VirtualRegister("ret_bool_value");
                // set assign to vreg
                dealAssign(vreg, 0, node.getExpr(), RegValue.RegSize, false);
                curBB.setJump(new Return(curBB, vreg));
            } else {
                visit(expr);
                curBB.setJump(new Return(curBB, expr.regValue));
            }
        }
    }

    // endregion

    // region visit expr

    private void dealSelfIncrDecr(ExprNode expr, ExprNode node, boolean isSuffix,
            boolean isIncr) {
        boolean needMemOp = needMemoryAccess(expr);
        boolean tmpWantAddr = curWantAddr;

        curWantAddr = false;
        visit(expr);

        if (isSuffix) { // x++
            VirtualRegister vreg = new VirtualRegister(null);
            // store expr-value first
            curBB.addInst(new Move(curBB, vreg, expr.regValue));
            node.regValue = vreg; // cause expr.regValue may change later
        } else {
            node.regValue = expr.regValue;
        }

        IntImm one = new IntImm(1);
        BinaryOpExprNode.Op op = isIncr ? BinaryOpExprNode.Op.ADD : BinaryOpExprNode.Op.SUB;

        if (needMemOp) {
            // get addr of expr
            curWantAddr = true;
            visit(expr);

            VirtualRegister vreg = new VirtualRegister(null);
            curBB.addInst(new Bin(curBB, vreg, op, expr.regValue, one));
            curBB.addInst(
                    new Store(curBB, vreg, RegValue.RegSize, expr.regValue, expr.offset));

            if (!isSuffix)
                expr.regValue = vreg;
        } else {
            // curBB.addInst(new Bin(curBB, (Register) expr.regValue,
            // op, expr.regValue, one));
            curBB.addInst(new Bin(curBB, expr.regValue, op, expr.regValue, one));
        }
        curWantAddr = tmpWantAddr;

    }

    public void visit(PrefixExprNode node) {
        VirtualRegister vreg;
        ExprNode sub = node.getExpr();

        switch (node.getOp()) {
        case DEC:
        case INC:
            dealSelfIncrDecr(sub, node, false, node.getOp() == PrefixExprNode.Op.INC);
            break;

        case POSI:
            node.regValue = sub.regValue;
            // BUG: FIX: TODO: no visit ???
            visit(sub);
            break;

        case NEGA:
            vreg = new VirtualRegister(null);
            node.regValue = vreg;
            visit(sub);
            curBB.addInst(new Uni(curBB, vreg, PrefixExprNode.Op.NEGA, sub.regValue));
            break;

        case BIT_NOT:
            vreg = new VirtualRegister(null);
            node.regValue = vreg;
            visit(sub);
            curBB.addInst(new Uni(curBB, vreg, PrefixExprNode.Op.BIT_NOT, sub.regValue));
            break;

        case LOGIC_NOT: // exchange true and false
            sub.setThen(node.getElse());
            sub.setElse(node.getThen());
            // FIX: no need to add ??
            visit(sub);

            break;
        }
    }

    public void visit(SuffixExprNode node) {
        dealSelfIncrDecr(node.getExpr(), node, true,
                node.getOp() == SuffixExprNode.Op.SUF_INC);
    }

    public void visit(BoolLiteralExprNode node) {
        node.regValue = new IntImm(node.getValue() ? 1 : 0);
    }

    public void visit(IntLiteralExprNode node) {
        node.regValue = new IntImm(node.getValue());
    }

    public void visit(StringLiteralExprNode node) {
        StaticString staticStr = root.getStaticStr(node.getValue());
        if (staticStr == null) {
            staticStr = new StaticString(node.getValue());
            root.putStaticStr(staticStr);
        }
        node.regValue = staticStr;
    }

    public void visit(NullExprNode node) {
        node.regValue = new IntImm(0);
    }

    // short-compute
    private void dealLogicalBinaryOp(BinaryOpExprNode node) {
        if (node.getOp() == BinaryOpExprNode.Op.LOGIC_AND) {
            node.getLhs().setThen(new BasicBlock(curFunc, "and_lhs_true"));
            node.getLhs().setElse(node.getElse());
            visit(node.getLhs());
            curBB = node.getLhs().getThen();
        } else if (node.getOp() == BinaryOpExprNode.Op.LOGIC_OR) {
            node.getLhs().setThen(node.getThen());
            node.getLhs().setElse(new BasicBlock(curFunc, "or_lhs_false"));
            visit(node.getLhs());
            curBB = node.getLhs().getElse();
        } else // UGLY: can del it
            throw new CompileError("invalid boolean binary operation");

        node.getRhs().setThen(node.getThen());
        node.getRhs().setElse(node.getElse());
        visit(node.getRhs());
    }

    private void dealBinaryOp(BinaryOpExprNode node) {
        if (node.getLhs().getType() instanceof StringType) {
            dealStringBinaryOp(node);
            return;
        }

        visit(node.getLhs());
        visit(node.getRhs());
        RegValue lhs = node.getLhs().regValue, rhs = node.getRhs().regValue;
        RegValue tmp;

        boolean bothConst = lhs instanceof IntImm && rhs instanceof IntImm;
        int lhsImm = 0, rhsImm = 0;
        if (lhs instanceof IntImm)
            lhsImm = ((IntImm) lhs).getValue();
        if (rhs instanceof IntImm)
            rhsImm = ((IntImm) rhs).getValue();

        BinaryOpExprNode.Op op = node.getOp();
        if (op == BinaryOpExprNode.Op.LOGIC_AND || op == BinaryOpExprNode.Op.LOGIC_OR)
            throw new CompileError("Never happen: don't split logic oper");

        switch (op) {

        case SH_L:
            tmp = new IntImm(lhsImm << rhsImm);
            if (!bothConst)
                root.hasDivShiftInst = true;
            break;

        case SH_R:
            tmp = new IntImm(lhsImm >> rhsImm);
            if (!bothConst)
                root.hasDivShiftInst = true;
            break;

        case MOD:
            if (bothConst && rhsImm == 0)
                throw new CompileError("div 0");
            tmp = new IntImm(lhsImm % rhsImm);

            if (!bothConst)
                root.hasDivShiftInst = true;
            break;

        case DIV:
            if (bothConst && rhsImm == 0)
                throw new CompileError("div 0");
            tmp = new IntImm(lhsImm / rhsImm);
            if (!bothConst)
                root.hasDivShiftInst = true;
            break;

        case MUL:
            tmp = new IntImm(lhsImm * rhsImm);
            break;

        case ADD:
            tmp = new IntImm(lhsImm + rhsImm);
            break;

        case SUB:
            tmp = new IntImm(lhsImm - rhsImm);
            break;

        case BIT_AND:
            tmp = new IntImm(lhsImm & rhsImm);
            break;

        case BIT_OR:
            tmp = new IntImm(lhsImm | rhsImm);
            break;

        case BIT_XOR:
            tmp = new IntImm(lhsImm ^ rhsImm);
            break;

        case GREATER:
            tmp = new IntImm((lhsImm > rhsImm) ? 1 : 0);
            if (!bothConst && lhs instanceof IntImm) {
                swap(rhs, lhs);
                op = BinaryOpExprNode.Op.LESS;
            }
            break;
        case LESS:
            tmp = new IntImm((lhsImm < rhsImm) ? 1 : 0);
            if (!bothConst && lhs instanceof IntImm) {
                swap(rhs, lhs);
                op = BinaryOpExprNode.Op.GREATER;
            }
            break;
        case GREATER_EQUAL:
            tmp = new IntImm((lhsImm >= rhsImm) ? 1 : 0);
            if (!bothConst && lhs instanceof IntImm) {
                swap(rhs, lhs);
                op = BinaryOpExprNode.Op.LESS_EQUAL;
            }
            break;
        case LESS_EQUAL:
            tmp = new IntImm((lhsImm <= rhsImm) ? 1 : 0);
            if (!bothConst && lhs instanceof IntImm) {
                swap(rhs, lhs);
                op = BinaryOpExprNode.Op.GREATER_EQUAL;
            }
            break;
        case EQUAL:
            tmp = new IntImm((lhsImm == rhsImm) ? 1 : 0);
            if (!bothConst && lhs instanceof IntImm) {
                swap(rhs, lhs);
            }
            break;

        case INEQUAL:
            tmp = new IntImm((lhsImm != rhsImm) ? 1 : 0);
            if (!bothConst && lhs instanceof IntImm) {
                swap(rhs, lhs);
            }
            break;

        default:
            throw new CompileError("Never happen binary not found");
        }

        if (bothConst) {
            node.regValue = tmp;
        } else {
            VirtualRegister vreg = new VirtualRegister(null);

            if (op != BinaryOpExprNode.Op.EQUAL || op != BinaryOpExprNode.Op.INEQUAL
                    || op != BinaryOpExprNode.Op.LESS_EQUAL || op != BinaryOpExprNode.Op.LESS
                    || op != BinaryOpExprNode.Op.GREATER_EQUAL
                    || op != BinaryOpExprNode.Op.GREATER) {
                node.regValue = vreg;
                curBB.addInst(new Bin(curBB, vreg, op, lhs, rhs));

            } else { // cmp

                curBB.addInst(new Cmp(curBB, vreg, op, lhs, rhs));
                if (node.getThen() != null) { // will jump
                    curBB.setJump(new CJump(curBB, vreg, node.getThen(), node.getElse()));
                } else { // will not jump
                    node.regValue = vreg;
                }

            }
        }
    }

    private void swap(Object a, Object b) {
        Object tmp;
        tmp = a;
        a = b;
        b = tmp;
    }

    private void dealStringBinaryOp(BinaryOpExprNode node) {
        if (!(node.getLhs().getType() instanceof StringType))
            throw new CompileError("Not happen: binary Oper: Type Error: invalid string ");

        visit(node.getLhs());
        visit(node.getRhs());

        BinaryOpExprNode.Op op = node.getOp();
        if (op == BinaryOpExprNode.Op.LOGIC_AND || op == BinaryOpExprNode.Op.LOGIC_OR)
            throw new CompileError("Never happen: don't split logic oper");

        RegValue lhs = node.getLhs().regValue, rhs = node.getRhs().regValue;
        boolean bothConst = lhs instanceof StaticString && rhs instanceof StaticString;

        String lhsImm = null, rhsImm = null;
        if (bothConst) {
            lhsImm = ((StaticString) lhs).getValue();

            rhsImm = ((StaticString) rhs).getValue();
        }

        Function calleeFunc;
        RegValue tmpReg;

        switch (node.getOp()) {
        case ADD:
            String tmpS = lhsImm + rhsImm;
            tmpReg = root.getStaticStr(tmpS);
            if (tmpReg == null) {
                tmpReg = new StaticString(tmpS);
                root.putStaticStr((StaticString) tmpReg);
            }

            calleeFunc = root.getBuiltInFunc(Tool.STRING_CONCAT_KEY);
            break;
        case EQUAL:
            tmpReg = new IntImm(lhsImm.equals(rhsImm) ? 1 : 0);
            calleeFunc = root.getBuiltInFunc(Tool.STRING_EQUAL_KEY);
            break;
        case INEQUAL:
            tmpReg = new IntImm(lhsImm.equals(rhsImm) ? 0 : 1);
            calleeFunc = root.getBuiltInFunc(Tool.STRING_INEQUAL_KEY);
            break;
        case LESS:// s2 < s3 : s2.com(s3) < 0
            tmpReg = new IntImm(lhsImm.compareTo(rhsImm) < 0 ? 1 : 0);
            calleeFunc = root.getBuiltInFunc(Tool.STRING_LESS_KEY);
            break;
        case LESS_EQUAL:
            tmpReg = new IntImm(lhsImm.compareTo(rhsImm) <= 0 ? 1 : 0);
            calleeFunc = root.getBuiltInFunc(Tool.STRING_LESS_EQUAL_KEY);
            break;
        case GREATER:
            tmpReg = new IntImm(lhsImm.compareTo(rhsImm) > 0 ? 1 : 0);

            // tmp = node.getLhs();
            // node.setLhs(node.getRhs());
            // node.setRhs(tmp);
            swap(node.getLhs(), node.getRhs());
            // FIX: Right ??
            calleeFunc = root.getBuiltInFunc(Tool.STRING_LESS_KEY);
            break;
        case GREATER_EQUAL:
            tmpReg = new IntImm(lhsImm.compareTo(rhsImm) >= 0 ? 1 : 0);

            swap(node.getLhs(), node.getRhs());
            calleeFunc = root.getBuiltInFunc(Tool.STRING_LESS_EQUAL_KEY);
            break;
        default:
            throw new CompileError("invalid string binary op");
        }

        if (bothConst) {
            node.regValue = tmpReg;
        } else {
            List<RegValue> args = new ArrayList<>();
            args.add(node.getLhs().regValue);
            args.add(node.getRhs().regValue);

            VirtualRegister vreg = new VirtualRegister(null);
            curBB.addInst(new Funcall(curBB, calleeFunc, args, vreg));

            if (node.getThen() != null) {
                curBB.setJump(new CJump(curBB, vreg, node.getThen(), node.getElse()));
            } else {
                node.regValue = vreg;
            }
        }
    }

    /** cur no need to care Lhs first */
    public void visit(BinaryOpExprNode node) {
        switch (node.getOp()) {
        case LOGIC_AND:
        case LOGIC_OR:
            dealLogicalBinaryOp(node);
            break;

        case MUL:
        case DIV:
        case MOD:
        case ADD:
        case SUB:
        case SH_L:
        case SH_R:
        case BIT_AND:
        case BIT_OR:
        case BIT_XOR:
        case GREATER:
        case LESS:
        case GREATER_EQUAL:
        case LESS_EQUAL:
        case EQUAL:
        case INEQUAL:
            dealBinaryOp(node);
            break;
        }
    }

    public void visit(MemberExprNode node) { // -> get Id or Func
        boolean tmpWantAddr = curWantAddr;
        curWantAddr = false;

        visit(node.getExpr());
        assignLhs = false;

        curWantAddr = tmpWantAddr;

        RegValue classAddr = node.getExpr().regValue;
        String className = ((ClassType) (node.getExpr().getType())).getName();

        ClassEntity classEntity = (ClassEntity) toplevelScope.get(className);
        VarEntity memberEntity = (VarEntity) classEntity.getScope().getCur(node.getMember());

        if (curWantAddr) {
            node.addrValue = classAddr;
            node.offset = memberEntity.getCurOffset();
        } else {
            VirtualRegister vreg = new VirtualRegister(null);
            node.regValue = vreg;
            // Pre-calced: what if FIX: class.arr ?? (can not init ??)
            curBB.addInst(new Load(curBB, vreg, memberEntity.getType().getRegSize(), classAddr,
                    memberEntity.getCurOffset()));

            if (node.getThen() != null)
                curBB.setJump(new CJump(curBB, node.regValue, node.getThen(), node.getElse()));
        }
    }

    public void visit(ArefExprNode node) {
        boolean tmpWantAddr = curWantAddr;
        curWantAddr = false;

        visit(node.getExpr());
        if (uselessStatic)
            return;

        assignLhs = false;
        visit(node.getIndex());

        curWantAddr = tmpWantAddr;

        VirtualRegister vreg = new VirtualRegister(null);
        // if still array or class, store addr,
        // if variable, store variable
        // but all of them is just size of this level
        IntImm elementSize = new IntImm(node.getType().getRegSize());

        // vreg <- real-addr = addr + size*index
        curBB.addInst(new Bin(curBB, vreg, BinaryOpExprNode.Op.MUL, node.getIndex().regValue,
                elementSize));
        curBB.addInst(
                new Bin(curBB, vreg, BinaryOpExprNode.Op.ADD, node.getExpr().regValue, vreg));

        if (curWantAddr) {
            node.addrValue = vreg;
            node.offset = RegValue.RegSize;
        } else { // variable
            curBB.addInst(new Load(curBB, vreg, node.getType().getRegSize(), vreg,
                    RegValue.RegSize));

            node.regValue = vreg;
            if (node.getThen() != null)
                curBB.setJump(new CJump(curBB, node.regValue, node.getThen(), node.getElse()));
        }
    }

    public void visit(FuncallExprNode node) {
        FuncEntity entity = node.funcEntity;
        String keyName = entity.getName();
        List<RegValue> args = new ArrayList<>();
        ExprNode thisExpr = null;

        if (entity.isMember()) {
            if (node.getExpr() instanceof MemberExprNode) {
                thisExpr = ((MemberExprNode) (node.getExpr())).getExpr(); // recursive till
                                                                          // else happen
            } else { // no more member
                if (curClass == null)
                    throw new CompileError("invalid member function call of this pointer");

                thisExpr = new ThisExprNode(null);
                thisExpr.setType(curClass.getType());
            }
            visit(thisExpr);

            String className;
            if (thisExpr.getType() instanceof ClassType)
                className = ((ClassType) (thisExpr.getType())).getName();
            else if (thisExpr.getType() instanceof ArrayType)
                className = Tool.ARRAY;
            else
                className = Tool.STRING;

            keyName = className + Tool.DOMAIN + keyName;
            args.add(thisExpr.regValue);
        }

        // call built-in functions
        if (entity.isBuiltIn) {
            dealBuiltInFuncCall(node, thisExpr, entity, keyName);
            return;
        }

        // params -- args
        for (ExprNode arg : node.getParam()) {
            visit(arg);
            args.add(arg.regValue);
        }

        Function func = root.getFunc(keyName);
        VirtualRegister vreg = new VirtualRegister(null);
        curBB.addInst(new Funcall(curBB, func, args, vreg));
        node.regValue = vreg;

        if (node.getThen() != null)
            curBB.setJump(new CJump(curBB, node.regValue, node.getThen(), node.getElse()));
    }

    /**
     * FIX: TODO: use as a single statement is useless, can delete assignNode-value,
     * but need assign lhs
     */
    public void visit(AssignExprNode node) {
        boolean needMemOp = needMemoryAccess(node.getLhs());
        curWantAddr = needMemOp;
        assignLhs = true;
        uselessStatic = false;

        visit(node.getLhs());

        assignLhs = false;
        curWantAddr = false;

        if (uselessStatic) {
            uselessStatic = false;
            return;
        }

        if (node.getRhs().getType() instanceof BoolType
                && !(node.getRhs() instanceof BoolLiteralExprNode)) {
            node.getRhs().setThen(new BasicBlock(curFunc, null));
            node.getRhs().setElse(new BasicBlock(curFunc, null));
        }
        visit(node.getRhs());

        RegValue destion;
        int addrOffset;
        if (needMemOp) {
            destion = node.getLhs().addrValue;
            addrOffset = node.getLhs().offset;
        } else {
            destion = node.getLhs().regValue;
            addrOffset = 0;
        }

        dealAssign(destion, addrOffset, node.getRhs(), RegValue.RegSize, needMemOp);
        node.regValue = node.getRhs().regValue;
    }

    @Override
    public void visit(ThisExprNode node) {
        VarEntity thisEntity = (VarEntity) curScope.get(Tool.THIS);
        node.regValue = thisEntity.register;
        if (node.getThen() != null) {
            curBB.setJump(new CJump(curBB, node.regValue, node.getThen(), node.getElse()));
        }
    }

    public void visit(IdentifierExprNode node) {
        // set useless
        VarEntity varEntity = node.entity;
        if ((varEntity.getType() instanceof ArrayType || varEntity.isGlobal)
                && varEntity.unUsed) {
            uselessStatic = true;
            return;
        }

        if (varEntity.register == null) { // has class but no reg, no init
            ThisExprNode thisNode = new ThisExprNode(null);
            thisNode.setType(new ClassType(curClass.getName()));

            MemberExprNode memNode = new MemberExprNode(thisNode, node.getIdentifier(), null);
            visit(memNode);

            if (curWantAddr) {
                node.addrValue = memNode.addrValue;
                node.offset = memNode.offset;
            } else {
                node.regValue = memNode.regValue;

                if (node.getThen() != null)
                    curBB.setJump(
                            new CJump(curBB, node.regValue, node.getThen(), node.getElse()));
            }

            // is actually this.identifier, which is a member accessing
            // expression
            node.setNeedMemOp(true);

        } else { // normal id
            node.regValue = varEntity.register;
            if (node.getThen() != null)
                curBB.setJump(new CJump(curBB, node.regValue, node.getThen(), node.getElse()));
        }
    }

    public void visit(NewExprNode node) {
        VirtualRegister vreg = new VirtualRegister(null);
        Type newType = node.getNewType().getType();

        if (newType instanceof ClassType) {
            String className = ((ClassType) newType).getName();
            ClassEntity classEntity = (ClassEntity) toplevelScope.get(className);
            curBB.addInst(new HeapAlloc(curBB, vreg, new IntImm(classEntity.memSize)));

            // call construction function
            String funcName = className + Tool.DOMAIN + className;
            Function irFunc = root.getFunc(funcName);
            if (irFunc != null) {
                List<RegValue> args = new ArrayList<>();
                args.add(vreg);
                curBB.addInst(new Funcall(curBB, irFunc, args, null));
            }

        } else if (newType instanceof ArrayType) {
            dealArrNew(node, vreg, null, 0);
        } else
            throw new CompileError("invalid new type");

        node.regValue = vreg;
    }

    // endregion

    // region utils

    /**
     * assign dest <- rhs.value (For VarDeclNode or Return or assign)
     * <p>
     * store mem(addr+offset) <- rhs (For assign)
     * <p>
     * maybe (split to then or else)add this block into curBB Assign, return,
     * varInit : processIRAssign
     */
    private void dealAssign(RegValue destion, int addrOffset, ExprNode rhs, int size,
            boolean needMemOp) {
        BasicBlock thenBB, elseBB;
        thenBB = rhs.getThen();
        elseBB = rhs.getElse();

        if (thenBB != null) { // has branch
            BasicBlock mergeBB = new BasicBlock(curFunc, null);
            // why IntImm? if has branch, no need to care value
            // FIX: BUG: TODO:still messy
            if (needMemOp) {
                thenBB.addInst(new Store(thenBB, new IntImm(1), RegValue.RegSize, destion,
                        addrOffset));
                elseBB.addInst(new Store(elseBB, new IntImm(0), RegValue.RegSize, destion,
                        addrOffset));
            } else {
                thenBB.addInst(new Move(thenBB, (VirtualRegister) destion, new IntImm(1)));
                elseBB.addInst(new Move(elseBB, (VirtualRegister) destion, new IntImm(0)));
            }

            // if then or else addInst, can not hasJump anymore
            if (!thenBB.hasJump())
                thenBB.setJump(new Jump(thenBB, mergeBB));
            if (!elseBB.hasJump())
                elseBB.setJump(new Jump(elseBB, mergeBB));

            curBB = mergeBB;
        } else { // no branch
            if (needMemOp)
                curBB.addInst(
                        new Store(curBB, rhs.regValue, RegValue.RegSize, destion, addrOffset));
            else
                curBB.addInst(new Move(curBB, (Register) destion, rhs.regValue));
        }
    }

    /**
     * print(A + B); -> print(A); print(B);
     * <p>
     * println(A + B); -> print(A); println(B);
     */
    private void dealPrintFuncCall(ExprNode arg, String funcName) {
        if (arg instanceof BinaryOpExprNode) {
            dealPrintFuncCall(((BinaryOpExprNode) arg).getLhs(), "print");
            dealPrintFuncCall(((BinaryOpExprNode) arg).getRhs(), funcName);
            return;
        }

        Function calleeFunc;
        List<RegValue> vArgs = new ArrayList<>();
        // FIX: needed to add printINT better or not ??
        if (arg instanceof FuncallExprNode
                && ((FuncallExprNode) arg).funcEntity.getName() == "toString") {
            // print(toString(n)); -> printInt(n);
            ExprNode intExpr = ((FuncallExprNode) arg).getParam().get(0);
            visit(intExpr);
            calleeFunc = root.getBuiltInFunc(funcName + "Int");
            vArgs.add(intExpr.regValue);
        } else {
            visit(arg);
            calleeFunc = root.getBuiltInFunc(funcName);
            vArgs.add(arg.regValue);
        }

        curBB.addInst(new Funcall(curBB, calleeFunc, vArgs, null));
    }

    private void dealBuiltInFuncCall(FuncallExprNode node, ExprNode thisExpr,
            FuncEntity entity, String keyName) {
        boolean tmpWantAddr = curWantAddr;
        curWantAddr = false;

        ExprNode arg0, arg1;
        VirtualRegister vreg;
        Function calleeFunc;
        List<RegValue> vArgs = new ArrayList<>();

        switch (keyName) {
        case Tool.PRINT_KEY:
        case Tool.PRINTLN_KEY:
            arg0 = node.getParam().get(0); // have to be string
            dealPrintFuncCall(arg0, keyName);
            break;

        case Tool.GETSTRING_KEY:
            vreg = new VirtualRegister("getString");
            vArgs.clear();

            calleeFunc = root.getBuiltInFunc(keyName);
            curBB.addInst(new Funcall(curBB, calleeFunc, vArgs, vreg));
            node.regValue = vreg;
            break;

        case Tool.GETINT_KEY:
            vreg = new VirtualRegister("getInt");
            vArgs.clear();

            calleeFunc = root.getBuiltInFunc(keyName);
            curBB.addInst(new Funcall(curBB, calleeFunc, vArgs, vreg));
            node.regValue = vreg;
            break;

        case Tool.TOSTRING_KEY:
            vreg = new VirtualRegister("toString");
            arg0 = node.getParam().get(0);
            visit(arg0);
            vArgs.clear();
            vArgs.add(arg0.regValue);

            calleeFunc = root.getBuiltInFunc(keyName);
            curBB.addInst(new Funcall(curBB, calleeFunc, vArgs, vreg));
            node.regValue = vreg;
            break;

        case Tool.SUBSTRING_KEY:
            vreg = new VirtualRegister("subString");
            arg0 = node.getParam().get(0);
            visit(arg0);
            arg1 = node.getParam().get(1);
            visit(arg1);
            vArgs.clear();
            vArgs.add(thisExpr.regValue);
            vArgs.add(arg0.regValue);
            vArgs.add(arg1.regValue);

            calleeFunc = root.getBuiltInFunc(keyName);
            curBB.addInst(new Funcall(curBB, calleeFunc, vArgs, vreg));
            node.regValue = vreg;
            break;

        case Tool.PARSEINT_KEY:
            vreg = new VirtualRegister("parseInt");
            vArgs.clear();
            vArgs.add(thisExpr.regValue);

            calleeFunc = root.getBuiltInFunc(keyName);
            curBB.addInst(new Funcall(curBB, calleeFunc, vArgs, vreg));
            node.regValue = vreg;
            break;

        case Tool.ORD_KEY:
            vreg = new VirtualRegister("ord");
            arg0 = node.getParam().get(0);
            visit(arg0);
            vArgs.clear();
            vArgs.add(thisExpr.regValue);
            vArgs.add(arg0.regValue);

            calleeFunc = root.getBuiltInFunc(keyName);
            curBB.addInst(new Funcall(curBB, calleeFunc, vArgs, vreg));
            node.regValue = vreg;

            // FIX: could be optimized by add and load
            break;

        case Tool.LENGTH_KEY:
        case Tool.SIZE_KEY:
            vreg = new VirtualRegister("size_length");

            curBB.addInst(new Load(curBB, vreg, RegValue.RegSize, thisExpr.regValue, 0));
            node.regValue = vreg;
            break;

        default:
            throw new CompileError("invalid built-in function call");
        }

        curWantAddr = tmpWantAddr;
    }

    /**
     * may have recurrence
     */
    private void dealArrNew(NewExprNode node, VirtualRegister oreg, RegValue addr, int idx) {
        VirtualRegister vreg = new VirtualRegister(null);
        ExprNode dim = node.getDims().get(idx);
        boolean tmpWantAddr = curWantAddr;
        curWantAddr = false;

        visit(dim);

        curWantAddr = tmpWantAddr;
        // vreg <- real-addr = addr + size*index
        curBB.addInst(new Bin(curBB, vreg, BinaryOpExprNode.Op.MUL, dim.regValue,
                new IntImm(RegValue.RegSize)));
        curBB.addInst(new Bin(curBB, vreg, BinaryOpExprNode.Op.ADD, vreg,
                new IntImm(RegValue.RegSize)));

        curBB.addInst(new HeapAlloc(curBB, vreg, vreg));
        curBB.addInst(new Store(curBB, dim.regValue, RegValue.RegSize, vreg, 0));

        // has more idx -> need loop-alloc
        if (idx < node.getDims().size() - 1) {
            VirtualRegister loop_idx = new VirtualRegister(null);
            VirtualRegister addrNow = new VirtualRegister(null);

            // init loop_idx and addr
            curBB.addInst(new Move(curBB, loop_idx, new IntImm(0)));
            curBB.addInst(new Move(curBB, addrNow, vreg));
            BasicBlock condBB = new BasicBlock(curFunc, "while_cond");
            BasicBlock bodyBB = new BasicBlock(curFunc, "while_body");
            BasicBlock afterBB = new BasicBlock(curFunc, "while_after");

            curBB.setJump(new Jump(curBB, condBB));

            // loop_idx ~ dim[idx].index_value
            // (cause high dim need idx*regSize times)
            curBB = condBB;
            BinaryOpExprNode.Op op = BinaryOpExprNode.Op.LESS;
            VirtualRegister cmpReg = new VirtualRegister(null);
            curBB.addInst(new Cmp(curBB, cmpReg, op, loop_idx, dim.regValue));
            curBB.setJump(new CJump(curBB, cmpReg, bodyBB, afterBB));

            // addrNow <- addrNow + regSize
            // deal next-dim
            // loop_idx <- loop_idx + 1
            // jump to condBB
            curBB = bodyBB;
            curBB.addInst(new Bin(curBB, addrNow, BinaryOpExprNode.Op.ADD, addrNow,
                    new IntImm(RegValue.RegSize)));
            dealArrNew(node, null, addrNow, idx + 1);
            curBB.addInst(new Bin(curBB, loop_idx, BinaryOpExprNode.Op.ADD, loop_idx,
                    new IntImm(1)));
            curBB.setJump(new Jump(curBB, condBB));

            curBB = afterBB;
        }

        if (idx == 0) { // 1-dim
            curBB.addInst(new Move(curBB, oreg, vreg));
        } else { // more dims
            curBB.addInst(new Store(curBB, vreg, RegValue.RegSize, addr, 0));
        }

    }

    /** need to use memory store and load */
    private boolean needMemoryAccess(ExprNode node) {
        if (node instanceof ArefExprNode || node instanceof MemberExprNode)
            return true;
        if ((node instanceof IdentifierExprNode
                && checkIdentiferMemberAccess((IdentifierExprNode) node)))
            return true;
        return false;
    }

    /** check class-mem access */
    private boolean checkIdentiferMemberAccess(IdentifierExprNode node) {
        // only need to check once
        if (!node.isChecked()) {
            if (curClass != null) {
                VarEntity varEntity = (VarEntity) curScope.get(node.getIdentifier());
                // FIX: if class.var is delivered with a register, no need
                // to set-needMem again
                node.setNeedMemOp(varEntity.register == null);
            } else {
                // Normal var
                node.setNeedMemOp(false);
            }
            node.setChecked(true);
        }
        return node.isNeedMemOp();
    }

    private void TwoRegOpTransformer() {
        for (Function irFunc : root.getFunc().values())
            for (BasicBlock bb : irFunc.getReversePostOrder())
                for (Quad inst : bb.getInsts()) {
                    if (!(inst instanceof Bin))
                        continue;
                    Bin BinInst = (Bin) inst;

                    if (BinInst.getDst() == BinInst.getLhs())
                        continue;

                    if (BinInst.getDst() == BinInst.getRhs()) {

                        if (BinInst.isCommutative()) {
                            swap(BinInst.getRhs(), BinInst.getLhs());
                        } else {
                            VirtualRegister vreg = new VirtualRegister("rhsBak");
                            BinInst.prependInst(
                                    new IRMove(BinInst.getParentBB(), vreg, BinInst.getRhs()));
                            BinInst.prependInst(new IRMove(BinInst.getParentBB(),
                                    BinInst.getDest(), BinInst.getLhs()));

                            BinInst.setLhs(BinInst.getDest());
                            BinInst.setRhs(vreg);
                        }

                    } else if (BinInst.getOp() != BinaryOpExprNode.Op.DIV
                            && BinInst.getOp() != BinaryOpExprNode.Op.MOD) {

                        BinInst.prependInst(new IRMove(BinInst.getParentBB(),
                                BinInst.getDest(), BinInst.getLhs()));
                        BinInst.setLhs(BinInst.getDest());

                    }
                }

    }
    // endregion
}