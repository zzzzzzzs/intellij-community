// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.project.impl

import com.intellij.configurationStore.runInAutoSaveDisabledMode
import com.intellij.conversion.ConversionResult
import com.intellij.conversion.ConversionService
import com.intellij.diagnostic.ActivityCategory
import com.intellij.diagnostic.StartUpMeasurer
import com.intellij.diagnostic.runMainActivity
import com.intellij.featureStatistics.fusCollectors.LifecycleUsageTriggerCollector
import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.GeneralSettings
import com.intellij.ide.IdeEventQueue
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.ide.lightEdit.LightEditCompatible
import com.intellij.ide.startup.impl.StartupManagerImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectBundle
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.impl.ProjectManagerExImpl.Companion.RUN_START_UP_ACTIVITIES
import com.intellij.openapi.startup.StartupManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.impl.welcomeScreen.WelcomeFrame
import com.intellij.platform.PlatformProjectOpenProcessor
import com.intellij.projectImport.ProjectAttachProcessor
import com.intellij.projectImport.ProjectOpenedCallback
import com.intellij.serviceContainer.processProjectComponents
import com.intellij.ui.IdeUICustomization
import com.intellij.util.io.delete
import org.jetbrains.annotations.ApiStatus
import java.awt.event.InvocationEvent
import java.io.IOException
import java.nio.file.*

@ApiStatus.Internal
open class ProjectManagerExImpl : ProjectManagerImpl() {
  companion object {
    @ApiStatus.Internal
    val RUN_START_UP_ACTIVITIES = Key.create<Boolean>("RUN_START_UP_ACTIVITIES")
  }

  final override fun createProject(name: String?, path: String): Project? {
    return newProject(toCanonicalName(path),
                      OpenProjectTask(isNewProject = true, runConfigurators = false).withProjectName(name))
  }

  final override fun newProject(projectName: String?, path: String, useDefaultProjectAsTemplate: Boolean, isDummy: Boolean): Project? {
    return newProject(toCanonicalName(path), OpenProjectTask(isNewProject = true,
                                                                        useDefaultProjectAsTemplate = useDefaultProjectAsTemplate,
                                                                        projectName = projectName))
  }

  final override fun loadAndOpenProject(originalFilePath: String): Project? {
    return openProject(toCanonicalName(originalFilePath), OpenProjectTask())
  }

  final override fun openProject(projectStoreBaseDir: Path, options: OpenProjectTask): Project? {
    return doOpenProject(projectStoreBaseDir, options, this)
  }

  @ApiStatus.Internal
  internal override fun doOpenProject(project: Project): Boolean {
    if (!addToOpened(project)) {
      return false
    }

    val app = ApplicationManager.getApplication()
    if (!app.isUnitTestMode && app.isDispatchThread) {
      LOG.warn("Do not open project in EDT")
    }

    try {
      openProject(project, ProgressManager.getInstance().progressIndicator)
    }
    catch (e: ProcessCanceledException) {
      app.invokeAndWait { closeProject(project, /* saveProject = */false, /* dispose = */true, /* checkCanClose = */false) }
      notifyProjectOpenFailed()
      return false
    }
    return true
  }

  override fun newProject(projectFile: Path, options: OpenProjectTask): Project? {
    if (ApplicationManager.getApplication().isUnitTestMode) {
      TEST_PROJECTS_CREATED++
      checkProjectLeaksInTests()
    }

    removeProjectDirContentOrFile(projectFile)

    val project = instantiateProject(projectFile, options)
    try {
      val template = if (options.useDefaultProjectAsTemplate) defaultProject else null
      initProject(projectFile, project, options.isRefreshVfsNeeded, template, ProgressManager.getInstance().progressIndicator)
      if (LOG_PROJECT_LEAKAGE_IN_TESTS) {
        myProjects.put(project, null)
      }
      return project
    }
    catch (t: Throwable) {
      if (ApplicationManager.getApplication().isUnitTestMode) {
        throw t
      }

      LOG.warn(t)
      try {
        val errorMessage = message(t)
        ApplicationManager.getApplication().invokeAndWait {
          Messages.showErrorDialog(errorMessage, ProjectBundle.message("project.load.default.error"))
        }
      }
      catch (e: NoClassDefFoundError) {
        // error icon not loaded
        LOG.info(e)
      }
      return null
    }
  }
}

private fun message(e: Throwable): String {
  var message = e.message ?: e.localizedMessage
  if (message != null) {
    return message
  }

  message = e.toString()
  val causeMessage = message(e.cause ?: return message)
  return "$message (cause: $causeMessage)"
}

private fun doOpenProject(projectStoreBaseDir: Path, options: OpenProjectTask, projectManager: ProjectManagerExImpl): Project? {
  val app = ApplicationManager.getApplication()
  if (ProjectManagerImpl.LOG.isDebugEnabled && !app.isUnitTestMode) {
    ProjectManagerImpl.LOG.debug("open project: $options", Exception())
  }

  if (options.project != null && projectManager.isProjectOpened(options.project)) {
    return null
  }

  val activity = StartUpMeasurer.startMainActivity("project opening preparation")
  if (!options.forceOpenInNewFrame) {
    val openProjects = projectManager.openProjects
    if (!openProjects.isNullOrEmpty()) {
      var projectToClose = options.projectToClose
      if (projectToClose == null) {
        // if several projects are opened, ask to reuse not last opened project frame, but last focused (to avoid focus switching)
        val lastFocusedFrame = IdeFocusManager.getGlobalInstance().lastFocusedFrame
        projectToClose = lastFocusedFrame?.project
        if (projectToClose == null || projectToClose is LightEditCompatible) {
          projectToClose = openProjects[openProjects.size - 1]
        }
      }
      if (checkExistingProjectOnOpen(projectToClose, options.callback, projectStoreBaseDir, projectManager)) {
        return null
      }
    }
  }

  val frameAllocator = if (app.isHeadlessEnvironment) ProjectFrameAllocator() else ProjectUiFrameAllocator(options, projectStoreBaseDir)
  val result = runInAutoSaveDisabledMode {
    frameAllocator.run {
      activity.end()
      val result: PrepareProjectResult?
      if (options.project == null) {
        result = prepareProject(options, projectStoreBaseDir, projectManager) ?: return@run null
      }
      else {
        result = PrepareProjectResult(options.project, null)
      }

      val project = result.project
      frameAllocator.projectLoaded(project)
      if (projectManager.doOpenProject(project)) {
        frameAllocator.projectOpened(project)
        result
      }
      else {
        null
      }
    }
  }

  if (result == null) {
    frameAllocator.projectNotLoaded(error = null)
    if (options.showWelcomeScreen) {
      WelcomeFrame.showIfNoProjectOpened()
    }
    return null
  }

  val project = result.project
  if (options.callback != null) {
    options.callback!!.projectOpened(project, result.module ?: ModuleManager.getInstance(project).modules[0])
  }
  return project
}

private fun prepareProject(options: OpenProjectTask, projectStoreBaseDir: Path, projectManager: ProjectManagerExImpl): PrepareProjectResult? {
  val project: Project?
  val indicator = ProgressManager.getInstance().progressIndicator
  if (options.isNewProject) {
    removeProjectDirContentOrFile(projectStoreBaseDir)
    project = instantiateProject(projectStoreBaseDir, options)
    val template = if (options.useDefaultProjectAsTemplate) projectManager.defaultProject else null
    ProjectManagerImpl.initProject(projectStoreBaseDir, project, options.isRefreshVfsNeeded, template, indicator)
  }
  else {
    var conversionResult: ConversionResult? = null
    if (options.runConversionBeforeOpen) {
      indicator?.text = IdeUICustomization.getInstance().projectMessage("progress.text.project.checking.configuration")
      conversionResult = runMainActivity("project conversion") {
        ConversionService.getInstance().convert(projectStoreBaseDir)
      }
      if (conversionResult.openingIsCanceled()) {
        return null
      }
      indicator?.text = ""
    }

    project = instantiateProject(projectStoreBaseDir, options)
    // template as null here because it is not a new project
    ProjectManagerImpl.initProject(projectStoreBaseDir, project, options.isRefreshVfsNeeded, null, indicator)
    if (conversionResult != null && !conversionResult.conversionNotNeeded()) {
      StartupManager.getInstance(project).runAfterOpened {
        conversionResult.postStartupActivity(project)
      }
    }
  }

  @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
  if (options.beforeOpen != null && !options.beforeOpen!!(project)) {
    return null
  }

  if (options.runConfigurators && (options.isNewProject || ModuleManager.getInstance(project).modules.isEmpty())) {
    val module = PlatformProjectOpenProcessor.runDirectoryProjectConfigurators(projectStoreBaseDir, project, options.isProjectCreatedWithWizard)
    options.preparedToOpen?.invoke(module)
    return PrepareProjectResult(project, module)
  }
  else {
    return PrepareProjectResult(project, module = null)
  }
}

private fun instantiateProject(projectStoreBaseDir: Path, options: OpenProjectTask): ProjectImpl {
  val activity = StartUpMeasurer.startMainActivity("project instantiation")
  val project = ProjectExImpl(projectStoreBaseDir, options.projectName)
  activity.end()
  options.beforeInit?.invoke(project)
  return project
}

private fun checkExistingProjectOnOpen(projectToClose: Project,
                                       callback: ProjectOpenedCallback?,
                                       projectDir: Path?,
                                       projectManager: ProjectManagerExImpl): Boolean {
  val settings = GeneralSettings.getInstance()
  val isValidProject = projectDir != null && ProjectUtil.isValidProjectPath(projectDir)
  if (projectDir != null && ProjectAttachProcessor.canAttachToProject() &&
      (!isValidProject || settings.confirmOpenNewProject == GeneralSettings.OPEN_PROJECT_ASK)) {
    val exitCode = ProjectUtil.confirmOpenOrAttachProject()
    if (exitCode == -1) {
      return true
    }
    else if (exitCode == GeneralSettings.OPEN_PROJECT_SAME_WINDOW) {
      if (!projectManager.closeAndDispose(projectToClose)) {
        return true
      }
    }
    else if (exitCode == GeneralSettings.OPEN_PROJECT_SAME_WINDOW_ATTACH) {
      if (PlatformProjectOpenProcessor.attachToProject(projectToClose, projectDir, callback)) {
        return true
      }
    }
    // process all pending events that can interrupt focus flow
    // todo this can be removed after taming the focus beast
    IdeEventQueue.getInstance().flushQueue()
  }
  else {
    val exitCode = ProjectUtil.confirmOpenNewProject(false)
    if (exitCode == GeneralSettings.OPEN_PROJECT_SAME_WINDOW) {
      if (!projectManager.closeAndDispose(projectToClose)) {
        return true
      }
    }
    else if (exitCode != GeneralSettings.OPEN_PROJECT_NEW_WINDOW) {
      // not in a new window
      return true
    }
  }
  return false
}

private fun openProject(project: Project, indicator: ProgressIndicator?) {
  val waitEdtActivity = StartUpMeasurer.startMainActivity("placing calling projectOpened on event queue")
  if (indicator != null) {
    indicator.text = if (ApplicationManager.getApplication().isInternal) "Waiting on event queue..." else ProjectBundle.message(
      "project.preparing.workspace")
    indicator.isIndeterminate = true
  }
  ApplicationManager.getApplication().invokeAndWait {
    waitEdtActivity.end()
    if (indicator != null && ApplicationManager.getApplication().isInternal) {
      indicator.text = "Running project opened tasks..."
    }

    ProjectManagerImpl.LOG.debug("projectOpened")
    LifecycleUsageTriggerCollector.onProjectOpened(project)
    val activity = StartUpMeasurer.startMainActivity("project opened callbacks")
    ApplicationManager.getApplication().messageBus.syncPublisher(ProjectManager.TOPIC).projectOpened(project)
    // https://jetbrains.slack.com/archives/C5E8K7FL4/p1495015043685628
    // projectOpened in the project components is called _after_ message bus event projectOpened for ages
    // old behavior is preserved for now (smooth transition, to not break all), but this order is not logical,
    // because ProjectComponent.projectOpened it is part of project initialization contract, but message bus projectOpened it is just an event
    // (and, so, should be called after project initialization)
    processProjectComponents(project.picoContainer) { component, pluginDescriptor ->
      StartupManagerImpl.runActivity {
        val componentActivity = StartUpMeasurer.startActivity(component.javaClass.name, ActivityCategory.PROJECT_OPEN_HANDLER,
                                                              pluginDescriptor.pluginId.idString)
        component.projectOpened()
        componentActivity.end()
      }
    }
    activity.end()
    ProjectImpl.ourClassesAreLoaded = true
  }

  val runStartUpActivitiesFlag = project.getUserData(RUN_START_UP_ACTIVITIES)
  if (runStartUpActivitiesFlag == null || runStartUpActivitiesFlag) {
    (StartupManager.getInstance(project) as StartupManagerImpl).projectOpened(indicator)
  }
}

// allow `invokeAndWait` inside startup activities
internal fun waitAndProcessInvocationEventsInIdeEventQueue(startupManager: StartupManagerImpl) {
  val eventQueue = IdeEventQueue.getInstance()
  while (true) {
    // getNextEvent() will block until an event has been posted by another thread, so,
    // peekEvent() is used to check that there is already some event in the queue
    if (eventQueue.peekEvent() == null) {
      if (startupManager.postStartupActivityPassed()) {
        break
      }
      else {
        continue
      }
    }

    val event = eventQueue.nextEvent
    if (event is InvocationEvent) {
      eventQueue.dispatchEvent(event)
    }
  }
}

private fun notifyProjectOpenFailed() {
  val app = ApplicationManager.getApplication()
  app.messageBus.syncPublisher(AppLifecycleListener.TOPIC).projectOpenFailed()
  if (!app.isUnitTestMode) {
    WelcomeFrame.showIfNoProjectOpened()
  }
}

private data class PrepareProjectResult(val project: Project, val module: Module?)

private fun toCanonicalName(filePath: String): Path {
  val file = Paths.get(filePath)
  try {
    if (SystemInfoRt.isWindows && FileUtil.containsWindowsShortName(filePath)) {
      return file.toRealPath(LinkOption.NOFOLLOW_LINKS)
    }
  }
  catch (e: InvalidPathException) {
  }
  catch (e: IOException) {
    // OK. File does not yet exist, so its canonical path will be equal to its original path.
  }
  return file
}

private fun removeProjectDirContentOrFile(projectFile: Path) {
  if (Files.isRegularFile(projectFile)) {
    try {
      Files.deleteIfExists(projectFile)
    }
    catch (ignored: IOException) {
    }
  }
  else {
    try {
      Files.newDirectoryStream(projectFile.resolve(Project.DIRECTORY_STORE_FOLDER)).use { directoryStream ->
        for (file in directoryStream) {
          file!!.delete()
        }
      }
    }
    catch (ignore: IOException) {
    }
  }
}