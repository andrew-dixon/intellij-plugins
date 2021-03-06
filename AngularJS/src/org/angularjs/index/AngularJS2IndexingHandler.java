package org.angularjs.index;

import com.intellij.lang.ASTNode;
import com.intellij.lang.javascript.JSElementTypes;
import com.intellij.lang.javascript.JSTokenTypes;
import com.intellij.lang.javascript.index.FrameworkIndexingHandler;
import com.intellij.lang.javascript.psi.*;
import com.intellij.lang.javascript.psi.ecma6.ES7Decorator;
import com.intellij.lang.javascript.psi.stubs.JSElementIndexingData;
import com.intellij.lang.javascript.psi.stubs.JSImplicitElement;
import com.intellij.lang.javascript.psi.stubs.impl.JSElementIndexingDataImpl;
import com.intellij.lang.javascript.psi.stubs.impl.JSImplicitElementImpl;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.impl.source.tree.TreeUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ObjectUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class AngularJS2IndexingHandler extends FrameworkIndexingHandler {
  @Override
  public void processCallExpression(JSCallExpression callExpression, @NotNull JSElementIndexingData outData) {
    if (callExpression instanceof JSNewExpression) {
      final JSExpression expression = callExpression.getMethodExpression();
      if (expression instanceof JSReferenceExpression) {
        final String name = ((JSReferenceExpression)expression).getReferenceName();
        final String restrictions = computeRestrictions(name);
        addImplicitElement(callExpression, (JSElementIndexingDataImpl)outData, restrictions, getSelectorName(callExpression));
      }
    }
  }

  @Override
  public boolean shouldCreateStubForCallExpression(ASTNode node) {
    final ASTNode first = node.getFirstChildNode();
    if (first.getElementType() == JSTokenTypes.NEW_KEYWORD) {
      final ASTNode ref = TreeUtil.findSibling(first, JSElementTypes.REFERENCE_EXPRESSION);
      if (ref != null){
        final ASTNode name = ref.getLastChildNode();
        if (name.getElementType() == JSTokenTypes.IDENTIFIER) {
          final String referencedName = name.getText();
          return computeRestrictions(referencedName) != null;
        }
      }

    }
    return false;
  }

  @Override
  public JSElementIndexingDataImpl processDecorator(ES7Decorator decorator, JSElementIndexingDataImpl outData) {
    final String name = decorator.getName();
    final String restrict = computeRestrictions(name);
    final String selectorName = getSelectorName(decorator);

    return addImplicitElement(decorator, outData, restrict, selectorName);
  }

  private static JSElementIndexingDataImpl addImplicitElement(PsiElement decorator,
                                                              JSElementIndexingDataImpl outData,
                                                              String restrict,
                                                              String selectorName) {
    if (restrict != null && !StringUtil.isEmpty(selectorName)) {
      if (outData == null) outData = new JSElementIndexingDataImpl();

      JSImplicitElementImpl.Builder elementBuilder = new JSImplicitElementImpl.Builder(selectorName, decorator)
        .setType(JSImplicitElement.Type.Class).setTypeString(restrict + ";;;");
      elementBuilder.setUserString("adi");
      final JSImplicitElementImpl implicitElement = elementBuilder.toImplicitElement();
      outData.addImplicitElement(implicitElement);
    }
    return outData;
  }

  @Nullable
  private static String getSelectorName(PsiElement decorator) {
    final JSArgumentList argumentList = PsiTreeUtil.getChildOfType(decorator, JSArgumentList.class);
    final JSExpression[] arguments = argumentList != null ? argumentList.getArguments() : null;
    final JSObjectLiteralExpression descriptor = ObjectUtils.tryCast(arguments != null && arguments.length > 0 ? arguments[0] : null,
                                                                     JSObjectLiteralExpression.class);
    final JSProperty selector = descriptor != null ? descriptor.findProperty("selector") : null;
    final JSExpression value = selector != null ? selector.getValue() : null;
    if (value instanceof JSLiteralExpression && ((JSLiteralExpression)value).isQuotedLiteral()) {
      final String selectorFormat = StringUtil.unquoteString(value.getText());
      final int start = selectorFormat.indexOf('[');
      final int end = selectorFormat.indexOf(']');
      if (start == 0 && end > 0) {
        return selectorFormat.substring(start + 1, end);
      }
      return selectorFormat;
    }
    return null;
  }

  @Nullable
  private static String computeRestrictions(String name) {
    return "Directive".equals(name) || "DirectiveAnnotation".equals(name) ? "A" :
           "Component".equals(name) || "ComponentAnnotation".equals(name)? "E" :
           null;
  }

  @Override
  public int getVersion() {
    return AngularIndexUtil.BASE_VERSION;
  }
}
