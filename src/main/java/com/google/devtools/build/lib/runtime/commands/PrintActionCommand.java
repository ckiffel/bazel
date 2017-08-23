// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.runtime.commands;

import com.google.common.base.Predicate;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata.MiddlemanType;
import com.google.devtools.build.lib.actions.ActionGraph;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.extra.DetailedExtraActionInfo;
import com.google.devtools.build.lib.actions.extra.ExtraActionSummary;
import com.google.devtools.build.lib.analysis.BuildView;
import com.google.devtools.build.lib.analysis.ConfiguredAttributeMapper;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.OutputGroupProvider;
import com.google.devtools.build.lib.analysis.PrintActionVisitor;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.buildtool.BuildResult;
import com.google.devtools.build.lib.buildtool.BuildTool;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.pkgcache.LoadingOptions;
import com.google.devtools.build.lib.runtime.BlazeCommand;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.util.io.OutErr;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsProvider;
import com.google.protobuf.TextFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Implements 'blaze print_action' by finding the Configured target[s] for the file[s] listed.
 *
 */
@Command(name = "print_action",
         builds = true,
         inherits = {BuildCommand.class},
         options = {PrintActionCommand.PrintActionOptions.class},
         help = "resource:print_action.txt",
         shortDescription = "Prints the command line args for compiling a file.",
         completion = "label",
         allowResidue = true,
         canRunInOutputDirectory = true)
public final class PrintActionCommand implements BlazeCommand {

  @Override
  public void editOptions(OptionsParser optionsParser) { }

  /**
   * Options for print_action, used to parse command-line arguments.
   */
  public static class PrintActionOptions extends OptionsBase {
    @Option(
      name = "print_action_mnemonics",
      allowMultiple = true,
      defaultValue = "",
      category = "print_action",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "Lists which mnemonics to filter print_action data by, no filtering takes place "
              + "when left empty."
    )
    public List<String> printActionMnemonics = new ArrayList<>();
  }

  @Override
  public ExitCode exec(CommandEnvironment env, OptionsProvider options) {
    LoadingOptions loadingOptions =
        options.getOptions(LoadingOptions.class);

    PrintActionOptions printActionOptions = options.getOptions(PrintActionOptions.class);
    PrintActionRunner runner = new PrintActionRunner(loadingOptions.compileOneDependency, options,
        env.getReporter().getOutErr(),
        options.getResidue(), Sets.newHashSet(printActionOptions.printActionMnemonics));
    return runner.printActionsForTargets(env);
  }

  /**
   * Contains all the logic to get extra_action information for print actions.
   * Maintains requires state to perform required analyses.
   */
  private class PrintActionRunner {
    private final boolean compileOneDependency;
    private final OptionsProvider options;
    private final OutErr outErr;
    private final List<String> requestedTargets;
    private final boolean keepGoing;
    private final ExtraActionSummary.Builder summaryBuilder;
    private final Predicate<ActionAnalysisMetadata> actionMnemonicMatcher;

    public PrintActionRunner(boolean compileOneDependency, OptionsProvider options, OutErr outErr,
        List<String> requestedTargets, final Set<String> printActionMnemonics) {
      this.compileOneDependency = compileOneDependency;
      this.options = options;
      this.outErr = outErr;
      this.requestedTargets = requestedTargets;
      BuildView.Options viewOptions = options.getOptions(BuildView.Options.class);
      keepGoing = viewOptions.keepGoing;
      summaryBuilder = ExtraActionSummary.newBuilder();
      actionMnemonicMatcher = new Predicate<ActionAnalysisMetadata>() {
        @Override
        public boolean apply(ActionAnalysisMetadata action) {
          return printActionMnemonics.isEmpty()
              || printActionMnemonics.contains(action.getMnemonic());
        }
      };
    }

    private ExitCode printActionsForTargets(CommandEnvironment env) {
      BuildResult result = gatherActionsForTargets(env, requestedTargets);
      if (result == null) {
        return ExitCode.PARSING_FAILURE;
      }
      if (hasFatalBuildFailure(result)) {
        env.getReporter().handle(Event.error("Build failed when printing actions"));
        return result.getExitCondition();
      }
      String action = TextFormat.printToString(summaryBuilder);
      if (!action.isEmpty()) {
        outErr.printOut(action);
        return result.getExitCondition();
      } else {
        env.getReporter().handle(Event.error("no actions to print were found"));
        return ExitCode.PARSING_FAILURE;
      }
    }

    private BuildResult gatherActionsForTargets(CommandEnvironment env, List<String> targets) {
      BlazeRuntime runtime = env.getRuntime();
      String commandName = PrintActionCommand.this.getClass().getAnnotation(Command.class).name();
      BuildRequest request = BuildRequest.create(commandName, options,
          runtime.getStartupOptionsProvider(),
          targets, outErr, env.getCommandId(), env.getCommandStartTime());
      BuildResult result = new BuildTool(env).processRequest(request, null);
      if (hasFatalBuildFailure(result)) {
        return result;
      }

      ActionGraph actionGraph = env.getSkyframeExecutor().getActionGraph(env.getReporter());

      for (ConfiguredTarget configuredTarget : result.getActualTargets()) {
        NestedSet<Artifact> filesToCompile = NestedSetBuilder.emptySet(Order.STABLE_ORDER);
        OutputGroupProvider outputGroupProvider = OutputGroupProvider.get(configuredTarget);
        if (outputGroupProvider != null) {
          filesToCompile =
              outputGroupProvider.getOutputGroup(OutputGroupProvider.FILES_TO_COMPILE);
        }
        if (!filesToCompile.isEmpty()) {
          if (compileOneDependency) {
            gatherActionsForFiles(configuredTarget, actionGraph, targets);
          } else {
            gatherActionsForTarget(configuredTarget, actionGraph);
          }
        } else {
          // TODO(rbraunstein): If a source is a member of a genrule and a cc_library don't
          // trigger this
          env.getReporter().handle(Event.error(
              null, configuredTarget + " is not a supported target kind"));
          return null;
        }
      }
      return result;
    }

    private BuildResult gatherActionsForFiles(
        ConfiguredTarget configuredTarget, ActionGraph actionGraph, List<String> files) {
      Set<String> filesDesired = new LinkedHashSet<>(files);
      ActionFilter filter = new DefaultActionFilter(filesDesired, actionMnemonicMatcher);

      gatherActionsForFile(configuredTarget, filter, actionGraph);
      return null;
    }

    private void gatherActionsForTarget(ConfiguredTarget configuredTarget,
        ActionGraph actionGraph) {
      if (!(configuredTarget.getTarget() instanceof Rule)) {
        return;
      }

      PrintActionVisitor visitor = new PrintActionVisitor(actionGraph, configuredTarget,
          actionMnemonicMatcher);

      // TODO(jvg): do we want to support ruleConfiguredTarget.getOutputArtifacts()?
      // We do for extra actions, but as we're past the action graph building phase,
      // we cannot call it without risking to trigger creation of OutputArtifacts post
      // graph building phase (not allowed). Right now we do not need them for our scenarios.
      visitor.visitWhiteNodes(configuredTarget.getProvider(FileProvider.class).getFilesToBuild());

      Iterable<ActionAnalysisMetadata> actions = visitor.getActions();
      for (ActionAnalysisMetadata action : actions) {
        if (action instanceof Action) {
          DetailedExtraActionInfo.Builder detail = DetailedExtraActionInfo.newBuilder();
          detail.setAction(((Action) action).getExtraActionInfo());
          summaryBuilder.addAction(detail);
        }
      }
    }

   /**
     * Looks for files to compile in the given configured target and outputs the corresponding
     * extra_action if the filter evaluates to {@code true}.
     */
    private void gatherActionsForFile(ConfiguredTarget configuredTarget, ActionFilter filter,
        ActionGraph actionGraph) {
      NestedSet<Artifact> artifacts = OutputGroupProvider.get(configuredTarget)
          .getOutputGroup(OutputGroupProvider.FILES_TO_COMPILE);

      if (artifacts.isEmpty()) {
        return;
      }

      for (Artifact artifact : artifacts) {
        ActionAnalysisMetadata action = actionGraph.getGeneratingAction(artifact);
        if (filter.shouldOutput(action, configuredTarget, actionGraph)) {
          if (action instanceof Action) {
            DetailedExtraActionInfo.Builder detail = DetailedExtraActionInfo.newBuilder();
            detail.setAction(((Action) action).getExtraActionInfo());
            summaryBuilder.addAction(detail);
          }
        }
      }
    }

    private boolean hasFatalBuildFailure(BuildResult result) {
      return result.getActualTargets() == null || (!result.getSuccess() && !keepGoing);
    }
  }

  /** Filter for extra actions. */
  private interface ActionFilter {
    /**
     * Returns true if the given action is not null and should be printed.
     */
    boolean shouldOutput(ActionAnalysisMetadata action, ConfiguredTarget configuredTarget,
        ActionGraph actionGraph);
  }

  /**
   * C++ headers are not plain vanilla action inputs: they do not show up in Action.getInputs(),
   * since the actual set of header files is the one discovered during include scanning.
   *
   * <p>However, since there is a scheduling dependency on the header files, we can use the
   * system to implement said scheduling dependency to figure them out. Thus, we go a-fishing in
   * the action graph reaching through error propagating middlemen: one of these exists for each
   * {@code CppCompilationContext} in the transitive closure of the rule.
   */
   private static void expandRecursiveHelper(ActionGraph actionGraph, Iterable<Artifact> artifacts,
       Set<Artifact> visited, Set<Artifact> result) {
    for (Artifact artifact : artifacts) {
      if (!visited.add(artifact)) {
        continue;
      }
      if (!artifact.isMiddlemanArtifact()) {
        result.add(artifact);
        continue;
      }

      ActionAnalysisMetadata middlemanAction = actionGraph.getGeneratingAction(artifact);
      if (middlemanAction.getActionType() != MiddlemanType.ERROR_PROPAGATING_MIDDLEMAN) {
        continue;
      }

      expandRecursiveHelper(actionGraph, middlemanAction.getInputs(), visited, result);
    }
  }

  private static void expandRecursive(ActionGraph actionGraph, Iterable<Artifact> artifacts,
      Set<Artifact> result) {
    expandRecursiveHelper(actionGraph, artifacts, Sets.<Artifact>newHashSet(), result);
  }

  /**
   * A stateful filter that keeps track of which files have already been covered. This makes it such
   * that blaze only prints out one action protobuf per file. This is important for headers. In
   * addition, this also handles C++ header files, which are not considered to be action inputs by
   * blaze (due to include scanning).
   *
   * <p>
   * As caveats, this only works for files that are given as proper relative paths, rather than
   * using target syntax, and only if the current working directory is the client root.
   */
  private static class DefaultActionFilter implements ActionFilter {
    private final Set<String> filesDesired;
    private final Predicate<ActionAnalysisMetadata> actionMnemonicMatcher;

    private DefaultActionFilter(Set<String> filesDesired,
        Predicate<ActionAnalysisMetadata> actionMnemonicMatcher) {
      this.filesDesired = filesDesired;
      this.actionMnemonicMatcher = actionMnemonicMatcher;
    }

    @Override
    public boolean shouldOutput(ActionAnalysisMetadata action, ConfiguredTarget configuredTarget,
        ActionGraph actionGraph) {
      if (action == null) {
        return false;
      }
      // Check all the inputs for the configured target against the file we want argv for.
      Set<Artifact> expandedArtifacts = Sets.newHashSet();
      expandRecursive(actionGraph, action.getInputs(), expandedArtifacts);
      for (Artifact input : expandedArtifacts) {
        if (filesDesired.remove(input.getRootRelativePath().getSafePathString())) {
          return actionMnemonicMatcher.apply(action);
        }
      }

      // C++ header files show up in the dependency on the Target, but not the ConfiguredTarget, so
      // we also check the target's header files there.
      RuleConfiguredTarget ruleConfiguredTarget = (RuleConfiguredTarget) configuredTarget;
      if (!ruleConfiguredTarget.getTarget().isAttrDefined("hdrs", BuildType.LABEL_LIST)) {
        return false;
      }
      List<Label> hdrs = ConfiguredAttributeMapper.of(ruleConfiguredTarget)
          .get("hdrs", BuildType.LABEL_LIST);
      if (hdrs != null) {
        for (Label hdrLabel : hdrs) {
          if (filesDesired.remove(hdrLabel.toPathFragment().getPathString())) {
            return actionMnemonicMatcher.apply(action);
          }
        }
      }
      return false; // no match
    }
  }
}
