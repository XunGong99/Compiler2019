package mxcompiler.ast.expression.unary;

import mxcompiler.ast.Dump;
import mxcompiler.ast.expression.ExprNode;

/** No longer support UnaryArithmeticOpNode 
*/
public class SuffixExprNode extends ExprNode {
    @Override
    public void _dump(Dump d) { d.print("Suffix Expr"); }

    public static enum Op {
        SUF_INC, SUF_DEC
    }

    private Op suffixOp;
    private ExprNode suffixExpr;

    public SuffixExprNode(Op suffixOp, ExprNode suffixExpr) {
        this.suffixOp = suffixOp;
        this.suffixExpr = suffixExpr;
    }

    public Op getOp() { return suffixOp; }
    public ExprNode getExpr() { return suffixExpr; }
    
}