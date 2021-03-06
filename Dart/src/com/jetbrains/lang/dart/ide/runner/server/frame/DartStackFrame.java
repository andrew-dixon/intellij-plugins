package com.jetbrains.lang.dart.ide.runner.server.frame;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.ui.ColoredTextContainer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.xdebugger.XDebuggerUtil;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XValueChildrenList;
import com.jetbrains.lang.dart.ide.runner.server.DartCommandLineDebugProcess;
import com.jetbrains.lang.dart.ide.runner.server.google.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class DartStackFrame extends XStackFrame {
  @NotNull private final DartCommandLineDebugProcess myDebugProcess;
  @NotNull private final VmCallFrame myVmCallFrame;
  @Nullable private final VmValue myException;
  @Nullable private final XSourcePosition mySourcePosition;
  @Nullable private final String myLocationUrl;

  public DartStackFrame(@NotNull final DartCommandLineDebugProcess debugProcess,
                        @NotNull final VmCallFrame vmCallFrame,
                        @Nullable final VmValue exception) {
    myDebugProcess = debugProcess;
    myVmCallFrame = vmCallFrame;
    myException = exception;

    final VmLocation location = vmCallFrame.getLocation();
    myLocationUrl = location == null ? null : location.getUnescapedUrl();
    if (myLocationUrl != null) {
      mySourcePosition = ApplicationManager.getApplication().runReadAction(new Computable<XSourcePosition>() {
        @Override
        public XSourcePosition compute() {
          final VirtualFile file = myDebugProcess.findFile(vmCallFrame.getIsolate(), vmCallFrame.getLibraryId(), myLocationUrl);
          final int line = location.getLineNumber(debugProcess.getVmConnection()) - 1;
          return file == null || line < 0 ? null : XDebuggerUtil.getInstance().createPosition(file, line);
        }
      });
    }
    else {
      mySourcePosition = null;
    }
  }

  @NotNull
  public VmIsolate getIsolate() {
    return myVmCallFrame.getIsolate();
  }

  @Nullable
  public Object getEqualityObject() {
    return myLocationUrl + "#" + myVmCallFrame.getFunctionName();
  }

  @Nullable
  @Override
  public XSourcePosition getSourcePosition() {
    return mySourcePosition;
  }

  public boolean isInDartSdkPatchFile() {
    return mySourcePosition != null && (mySourcePosition.getFile() instanceof LightVirtualFile);
  }

  @Nullable
  public XDebuggerEvaluator getEvaluator() {
    return new DartDebuggerEvaluator(myDebugProcess, myVmCallFrame);
  }

  @Override
  public void computeChildren(final @NotNull XCompositeNode node) {
    final List<VmVariable> locals = myVmCallFrame.getLocals();

    final XValueChildrenList childrenList = new XValueChildrenList(locals == null ? 1 : locals.size() + 1);

    if (myException != null) {
      childrenList.add(new DartValue(myDebugProcess, DartValue.NODE_NAME_EXCEPTION, myException, true));
    }

    if (locals != null) {
      for (final VmVariable localVar : locals) {
        final VmValue vmValue = localVar.getValue();
        if (vmValue != null) {
          childrenList.add(new DartValue(myDebugProcess, localVar.getName(), localVar.getValue(), false));
        }
      }
    }

    if (myVmCallFrame.getIsolate() != null && myVmCallFrame.getLibraryId() != -1) {
      // todo make library info presentable
      //childrenList.add(new DartLibraryValue(myDebugProcess, myVmCallFrame.getIsolate(), myVmCallFrame.getLibraryId()));
    }

    node.addChildren(childrenList, true);
  }

  @Override
  public void customizePresentation(@NotNull ColoredTextContainer component) {
    if (myVmCallFrame.getFunctionName() != null) {
      component.append(DebuggerUtils.demangleVmName(myVmCallFrame.getFunctionName()), SimpleTextAttributes.REGULAR_ATTRIBUTES);
    }

    if (mySourcePosition != null) {
      final String text = " (" + mySourcePosition.getFile().getName() + ":" + (mySourcePosition.getLine() + 1) + ")";
      component.append(text, SimpleTextAttributes.GRAY_ATTRIBUTES);
    }
    else if (myLocationUrl != null) {
      component.append(" (" + myLocationUrl + ")", SimpleTextAttributes.GRAY_ATTRIBUTES);
    }

    component.setIcon(AllIcons.Debugger.StackFrame);
  }
}
