/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package org.jetbrains.osgi.bnd.run;

import aQute.bnd.build.ProjectLauncher;
import aQute.bnd.build.Workspace;
import com.intellij.debugger.ui.HotSwapUI;
import com.intellij.debugger.ui.HotSwapVetoableListener;
import com.intellij.execution.CantRunException;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.JavaCommandLineState;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.compiler.CompilationStatusListener;
import com.intellij.openapi.compiler.CompileContext;
import com.intellij.openapi.compiler.CompilerTopics;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.FileAttributes;
import com.intellij.openapi.util.io.FileSystemUtil;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;
import org.osmorc.i18n.OsmorcBundle;

import java.io.File;
import java.util.Map;

import static com.intellij.openapi.util.Pair.pair;

public class BndLaunchState extends JavaCommandLineState implements CompilationStatusListener, HotSwapVetoableListener {
  private static final Logger LOG = Logger.getInstance(BndLaunchState.class);
  private static final Pair<Long, Long> MISSING_BUNDLE = pair(0l, 0l);

  private static final Map<String, NotificationGroup> ourNotificationGroups = ContainerUtil.newHashMap();

  private final BndRunConfigurationBase.Launch myConfiguration;
  private final Project myProject;
  private final NotificationGroup myNotifications;
  private final ProjectLauncher myLauncher;
  private final Map<String, Pair<Long, Long>> myBundleStamps;

  public BndLaunchState(@NotNull ExecutionEnvironment environment, @NotNull BndRunConfigurationBase.Launch configuration) throws ExecutionException {
    super(environment);

    myConfiguration = configuration;
    myProject = myConfiguration.getProject();

    String toolWindowId = environment.getExecutor().getToolWindowId();
    NotificationGroup notificationGroup = ourNotificationGroups.get(toolWindowId);
    if (notificationGroup == null) {
      String name = BndRunConfigurationType.getInstance().getDisplayName() + " (" + toolWindowId + ")";
      notificationGroup = NotificationGroup.toolWindowGroup(name, toolWindowId);
      ourNotificationGroups.put(toolWindowId, notificationGroup);
    }
    myNotifications = notificationGroup;

    File runFile = new File(myConfiguration.bndRunFile);
    if (!runFile.isFile()) {
      throw new CantRunException(OsmorcBundle.message("bnd.run.configuration.invalid", runFile));
    }

    try {
      myLauncher = Workspace.getRun(runFile).getProjectLauncher();
      myLauncher.prepare();
    }
    catch (Exception e) {
      LOG.info(e);
      throw new CantRunException(OsmorcBundle.message("bnd.run.configuration.cannot.run", runFile, e.getMessage()));
    }

    myBundleStamps = ContainerUtil.newHashMap();
    bundlesChanged();
  }

  @Override
  protected JavaParameters createJavaParameters() throws ExecutionException {
    return BndLaunchUtil.createJavaParameters(myConfiguration, myLauncher);
  }

  @NotNull
  @Override
  protected OSProcessHandler startProcess() throws ExecutionException {
    OSProcessHandler handler = super.startProcess();

    final MessageBusConnection connection = myProject.getMessageBus().connect();
    connection.subscribe(CompilerTopics.COMPILATION_STATUS, this);

    final HotSwapUI hotSwapManager = HotSwapUI.getInstance(myProject);
    hotSwapManager.addListener(this);

    handler.addProcessListener(new ProcessAdapter() {
      @Override
      public void processTerminated(ProcessEvent event) {
        connection.disconnect();
        hotSwapManager.removeListener(BndLaunchState.this);
        myLauncher.cleanup();
      }
    });

    return handler;
  }

  @Override
  public void compilationFinished(boolean aborted, int errors, int warnings, CompileContext context) {
    if (!aborted && errors == 0 && bundlesChanged()) {
      try {
        myLauncher.update();
        ApplicationManager.getApplication().invokeLater(new Runnable() {
          @Override
          public void run() {
            myNotifications.createNotification(OsmorcBundle.message("bnd.run.reloaded.text"), NotificationType.INFORMATION).notify(myProject);
          }
        }, ModalityState.NON_MODAL);
      }
      catch (Exception e) {
        LOG.error(e);
      }
    }
  }

  private boolean bundlesChanged() {
    boolean changed = false;

    for (String bundle : myLauncher.getRunBundles()) {
      FileAttributes attributes = FileSystemUtil.getAttributes(bundle);
      Pair<Long, Long> current = attributes != null ? pair(attributes.lastModified, attributes.length) : MISSING_BUNDLE;
      if (!current.equals(myBundleStamps.get(bundle))) {
        myBundleStamps.put(bundle, current);
        changed = true;
      }
    }

    return changed;
  }

  @Override
  public void fileGenerated(String outputRoot, String relativePath) { }

  @Override
  public boolean shouldHotSwap(CompileContext context) {
    return false;
  }
}
