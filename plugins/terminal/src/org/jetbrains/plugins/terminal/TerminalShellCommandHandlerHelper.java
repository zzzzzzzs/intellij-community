// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.terminal;

import com.google.common.base.Ascii;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.*;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.Experiments;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.terminal.TerminalShellCommandHandler;
import com.intellij.util.Alarm;
import com.jediterm.terminal.*;
import com.jediterm.terminal.model.CharBuffer;
import com.jediterm.terminal.model.TerminalTextBuffer;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.terminal.arrangement.TerminalWorkingDirectoryManager;

import javax.swing.*;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.Arrays;
import java.util.function.Function;

public final class TerminalShellCommandHandlerHelper {
  private static final Logger LOG = Logger.getInstance(TerminalShellCommandHandler.class);
  @NonNls private static final String TERMINAL_CUSTOM_COMMANDS_GOT_IT = "TERMINAL_CUSTOM_COMMANDS_GOT_IT";
  @NonNls private static final String GOT_IT = "got_it";
  @NonNls private static final String FEATURE_ID = "terminal.shell.command.handling";

  private static Experiments ourExperiments;

  private final ShellTerminalWidget myWidget;
  private final Alarm myAlarm;
  private volatile String myWorkingDirectory;
  private volatile Boolean myHasRunningCommands;
  private PropertiesComponent myPropertiesComponent;
  private final SingletonNotificationManager mySingletonNotificationManager =
    new SingletonNotificationManager(NotificationGroup.toolWindowGroup("Terminal", TerminalToolWindowFactory.TOOL_WINDOW_ID),
                                     NotificationType.INFORMATION, null);

  TerminalShellCommandHandlerHelper(@NotNull ShellTerminalWidget widget) {
    myWidget = widget;
    myAlarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, widget);
  }

  public void processKeyPressed() {
    if (isFeatureEnabled()) {
      myAlarm.cancelAllRequests();
      myAlarm.addRequest(() -> {
        highlightMatchedCommand(myWidget.getProject());
      }, 50);
    }
  }

  public static boolean isFeatureEnabled() {
    Experiments experiments = ourExperiments;
    if (experiments == null) {
      experiments = ReadAction.compute(() -> {
        return ApplicationManager.getApplication().isDisposed() ? null : Experiments.getInstance();
      });
      ourExperiments = experiments;
    }
    return experiments != null && experiments.isFeatureEnabled(FEATURE_ID);
  }

  private void highlightMatchedCommand(@NotNull Project project) {
    if (!isEnabledForProject()) {
      myWidget.getTerminalPanel().setFindResult(null);
      return;
    }

    //highlight matched command
    String command = myWidget.getTypedShellCommand();
    SubstringFinder.FindResult result =
      TerminalShellCommandHandler.Companion.matches(project, getWorkingDirectory(), !hasRunningCommands(), command)
      ? searchMatchedCommand(command, true) : null;
    myWidget.getTerminalPanel().setFindResult(result);

    //show notification
    if (getPropertiesComponent().getBoolean(TERMINAL_CUSTOM_COMMANDS_GOT_IT, false)) {
      return;
    }

    if (result != null) {
      AnAction action = ActionManager.getInstance().getAction("Terminal.SmartCommandExecution");
      if (action == null) {
        LOG.error("Terminal Smart Execution action isn't registered");
        return;
      }
      String title = TerminalBundle.message("smart_command_execution.notification.title");
      String content = TerminalBundle.message("smart_command_execution.notification.text", KeymapUtil.getFirstKeyboardShortcutText(action));
      mySingletonNotificationManager.notify(title, content, project, null,
                                            new NotificationAction(TerminalBundle.message("smart_command_execution.notification.got.it")) {
                                              @Override
                                              public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification notification) {
                                                getPropertiesComponent().setValue(TERMINAL_CUSTOM_COMMANDS_GOT_IT, true, false);
                                              }
                                            });
    }
  }

  private boolean isEnabledForProject() {
    return getPropertiesComponent().getBoolean(TerminalCommandHandlerCustomizer.TERMINAL_CUSTOM_COMMAND_EXECUTION, true);
  }

  @NotNull
  private PropertiesComponent getPropertiesComponent() {
    PropertiesComponent propertiesComponent = myPropertiesComponent;
    if (propertiesComponent == null) {
      propertiesComponent = ReadAction.compute(() -> PropertiesComponent.getInstance());
      myPropertiesComponent = propertiesComponent;
    }
    return propertiesComponent;
  }

  @Nullable
  private String getWorkingDirectory() {
    String workingDirectory = myWorkingDirectory;
    if (workingDirectory == null) {
      workingDirectory = StringUtil.notNullize(TerminalWorkingDirectoryManager.getWorkingDirectory(myWidget, null));
      myWorkingDirectory = workingDirectory;
    }
    return StringUtil.nullize(workingDirectory);
  }

  private boolean hasRunningCommands() {
    Boolean hasRunningCommands = myHasRunningCommands;
    if (hasRunningCommands == null) {
      hasRunningCommands = myWidget.hasRunningCommands();
      myHasRunningCommands = hasRunningCommands;
    }
    return hasRunningCommands;
  }

  @Nullable
  private SubstringFinder.FindResult searchMatchedCommand(@NotNull String pattern, boolean ignoreCase) {
    if (pattern.length() == 0) {
      return null;
    }

    final SubstringFinder finder = new SubstringFinder(pattern, ignoreCase);
    StyledTextConsumer consumer = new StyledTextConsumerAdapter() {
      @Override
      public void consume(int x, int y, @NotNull TextStyle style, @NotNull CharBuffer characters, int startRow) {
        for (int i = 0; i < characters.length(); i++) {
          finder.nextChar(x, y - startRow, characters, i);
        }
      }
    };

    myWidget.processTerminalBuffer((Function<TerminalTextBuffer, Void>)textBuffer -> {
      textBuffer.processScreenLines(myWidget.getLineNumberAtCursor(), 1, consumer);
      return null;
    });

    return finder.getResult();
  }

  public boolean processEnterKeyPressed(@NotNull String command, @NotNull KeyEvent keyPressed) {
    if (!isFeatureEnabled() || !isEnabledForProject()) {
      onShellCommandExecuted();
      return false;
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("typed shell command to execute: " + command);
    }
    myAlarm.cancelAllRequests();

    if (!matchSmartCommandAction(keyPressed)) {
      onShellCommandExecuted();
      return false;
    }

    Project project = myWidget.getProject();
    String workingDirectory = getWorkingDirectory();
    boolean localSession = !hasRunningCommands();
    if (!TerminalShellCommandHandler.Companion.matches(project, workingDirectory, localSession, command)) {
      onShellCommandExecuted();
      return false;
    }

    TerminalUsageTriggerCollector.Companion.triggerSmartCommandExecuted(project, command);
    TerminalShellCommandHandler.Companion.executeShellCommandHandler(myWidget.getProject(), getWorkingDirectory(),
                                                                     !hasRunningCommands(), command);
    clearTypedCommand(command);
    return true;
  }

  private void onShellCommandExecuted() {
    myWorkingDirectory = null;
    myHasRunningCommands = null;
  }

  private void clearTypedCommand(@NotNull String command) {
    TtyConnector connector = myWidget.getTtyConnector();
    byte[] array = new byte[command.length()];
    Arrays.fill(array, Ascii.BS);
    try {
      connector.write(array);
    }
    catch (IOException e) {
      LOG.info("Cannot clear shell command " + command, e);
    }
  }

  static boolean matchSmartCommandAction(@NotNull KeyEvent e) {
    final KeyboardShortcut eventShortcut = new KeyboardShortcut(KeyStroke.getKeyStrokeForEvent(e), null);
    AnAction action = ActionManager.getInstance().getAction("Terminal.SmartCommandExecution");
    return action != null &&
           Arrays.stream(action.getShortcutSet().getShortcuts()).anyMatch(sc -> sc.isKeyboard() && sc.startsWith(eventShortcut));
  }
}
