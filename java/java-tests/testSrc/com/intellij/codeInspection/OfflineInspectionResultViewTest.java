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

/*
 * User: anna
 * Date: 14-Jan-2007
 */
package com.intellij.codeInspection;

import com.intellij.codeInsight.daemon.HighlightDisplayKey;
import com.intellij.codeInspection.actions.ViewOfflineResultsAction;
import com.intellij.codeInspection.defUse.DefUseInspection;
import com.intellij.codeInspection.defUse.DefUseInspectionBase;
import com.intellij.codeInspection.ex.*;
import com.intellij.codeInspection.offline.OfflineProblemDescriptor;
import com.intellij.codeInspection.offlineViewer.OfflineProblemDescriptorNode;
import com.intellij.codeInspection.offlineViewer.OfflineViewParseUtil;
import com.intellij.codeInspection.ui.InspectionResultsView;
import com.intellij.codeInspection.ui.InspectionTree;
import com.intellij.codeInspection.ui.InspectionTreeNode;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ex.PathManagerEx;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.psi.PsiElement;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.TestSourceBasedTestCase;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.tree.TreeUtil;
import com.siyeh.ig.bugs.EqualsWithItselfInspection;
import gnu.trove.THashMap;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class OfflineInspectionResultViewTest extends TestSourceBasedTestCase {
  private InspectionResultsView myView;
  private LocalInspectionToolWrapper myUnusedToolWrapper;
  private LocalInspectionToolWrapper myDataFlowToolWrapper;

  private static String varMessage(String name) {
    return InspectionsBundle.message("inspection.unused.assignment.problem.descriptor1", "'" + name + "'");
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();

    HighlightDisplayKey key = HighlightDisplayKey.find(DefUseInspectionBase.SHORT_NAME);
    if (key == null) {
      HighlightDisplayKey.register(DefUseInspectionBase.SHORT_NAME);
    }

    final InspectionProfileImpl profile = new InspectionProfileImpl("test") {
      @Override
      public boolean isToolEnabled(final HighlightDisplayKey key, PsiElement element) {
        return Comparing.strEqual(key.toString(), DefUseInspectionBase.SHORT_NAME);
      }

      @Override
      @NotNull
      public InspectionToolWrapper[] getInspectionTools(PsiElement element) {
        return new InspectionToolWrapper[]{myUnusedToolWrapper};
      }

      @Override
      @NotNull
      public ModifiableModel getModifiableModel() {
        return new InspectionProfileImpl("test") {
          @Override
          @NotNull
          public InspectionToolWrapper[] getInspectionTools(PsiElement element) {
            return new InspectionToolWrapper[]{myUnusedToolWrapper};
          }

          @Override
          public boolean isToolEnabled(final HighlightDisplayKey key, PsiElement element) {
            return Comparing.strEqual(key.toString(), DefUseInspectionBase.SHORT_NAME);
          }
        };
      }
    };

    myView = ViewOfflineResultsAction.showOfflineView(getProject(), parse(), profile, "");
    myUnusedToolWrapper = new LocalInspectionToolWrapper(new DefUseInspection());
    myDataFlowToolWrapper = new LocalInspectionToolWrapper(new EqualsWithItselfInspection());

    final Map<String, Tools> tools = myView.getGlobalInspectionContext().getTools();
    for (LocalInspectionToolWrapper tool : ContainerUtil.ar(myUnusedToolWrapper, myDataFlowToolWrapper)) {
      profile.addTool(getProject(), tool, new THashMap<>());
      tools.put(tool.getShortName(), new ToolsImpl(tool, tool.getDefaultLevel(), true));
      tool.initialize(myView.getGlobalInspectionContext());
    }
}

  private Map<String, Map<String, Set<OfflineProblemDescriptor>>> parse() throws IOException {
    final String moduleName = getModule().getName();
    final Map<String, Map<String, Set<OfflineProblemDescriptor>>> map = new HashMap<String, Map<String, Set<OfflineProblemDescriptor>>>();
    final File res = new File(PathManagerEx.getTestDataPath(), getTestPath() + File.separator + "res");
    final File[] files = res.listFiles();
    assert files != null;
    for (File file : files) {
      final String name = file.getName();
      final String problems = FileUtil.loadFile(file);
      final Map<String, Set<OfflineProblemDescriptor>> descriptors = OfflineViewParseUtil.parse(problems);
      for (Set<OfflineProblemDescriptor> problemDescriptors : descriptors.values()) {
        for (OfflineProblemDescriptor descriptor : problemDescriptors) {
          descriptor.setModule(moduleName);
        }
      }
      map.put(name.substring(0, name.lastIndexOf('.')), descriptors);
    }
    return map;
  }

  @Override
  protected void tearDown() throws Exception {
    Disposer.dispose(myView);
    myView = null;
    myUnusedToolWrapper = null;
    myDataFlowToolWrapper = null;
    super.tearDown();
  }

  public void testOfflineWithInvalid() throws Exception {
    ApplicationManager.getApplication().runWriteAction(() -> getJavaFacade().findClass("Test2").getContainingFile().delete());
    myView.getGlobalInspectionContext().getUIOptions().SHOW_STRUCTURE = true;
    InspectionTree tree = updateTree();
    TreeUtil.expandAll(tree);
    PlatformTestUtil.assertTreeEqual(tree, "-" + getProject() + "\n" +
                                           " -Probable bugs\n" +
                                           "  -" + myDataFlowToolWrapper + "\n" +
                                           "   -Module: 'testOfflineWithInvalid'\n" +
                                           "    -<default>\n" +
                                           "     -Test\n" +
                                           "      -m()\n" +
                                           "       Identical qualifier and argument to 'equals()' call\n" +
                                           "     -null\n" +
                                           "      Identical qualifier and argument to <code>equals()</code> call\n"
                                           + "  -" + myUnusedToolWrapper + "\n"
                                           + "   -" + getModule().toString() + "\n"
                                           + "    -<default>\n"
                                           + "     -Test\n"
                                           + "      -foo()\n"
                                           + "       " + varMessage("j") + "\n"
                                           + "      -main(String[])\n"
                                           + "       " + varMessage("test") + "\n"
                                           + "      -f()\n"
                                           + "       -D\n"
                                           + "        -b()\n"
                                           + "         " + varMessage("r") + "\n"
                                           + "         -anonymous (java.lang.Runnable)\n"
                                           + "          -run()\n"
                                           + "           " + varMessage("i") + "\n"
                                           + "      -ff()\n"
                                           + "       " + varMessage("d") + "\n"
                                           + "       " + varMessage("a") + "\n");
    tree.setSelectionRow(8);
    final OfflineProblemDescriptorNode node =
      (OfflineProblemDescriptorNode)tree.getSelectionModel().getSelectionPath().getLastPathComponent();
    assertFalse(node.isValid());
  }

  public void testOfflineView() throws Exception {
    myView.getGlobalInspectionContext().getUIOptions().SHOW_STRUCTURE = true;
    InspectionTree tree = updateTree();
    TreeUtil.expandAll(tree);
    PlatformTestUtil.assertTreeEqual(tree, "-" + getProject() + "\n" +
                                           " -Probable bugs\n" +
                                           "  -" + myDataFlowToolWrapper + "\n" +
                                           "   -Module: 'testOfflineView'\n" +
                                           "    -<default>\n" +
                                           "     -Test\n" +
                                           "      -m()\n" +
                                           "       Identical qualifier and argument to 'equals()' call\n" +
                                           "     -Test2\n" +
                                           "      -m123()\n" +
                                           "       Identical qualifier and argument to 'equals()' call\n"
                                           + "  -" + myUnusedToolWrapper + "\n"
                                           + "   -" + getModule().toString() + "\n"
                                           + "    -<default>\n"
                                           + "     -Test\n"
                                           + "      -foo()\n"
                                           + "       " + varMessage("j") + "\n"
                                           + "      -main(String[])\n"
                                           + "       " + varMessage("test") + "\n"
                                           + "      -f()\n"
                                           + "       -D\n"
                                           + "        -b()\n"
                                           + "         " + varMessage("r") + "\n"
                                           + "         -anonymous (java.lang.Runnable)\n"
                                           + "          -run()\n"
                                           + "           " + varMessage("i") + "\n"
                                           + "      -ff()\n"
                                           + "       " + varMessage("d") + "\n"
                                           + "       " + varMessage("a") + "\n");
                                     myView.getGlobalInspectionContext().getUIOptions().SHOW_STRUCTURE = false;
    tree = updateTree();
    PlatformTestUtil.assertTreeEqual(tree, "-" + getProject() + "\n"
                                           + " -Probable bugs\n"
                                           + "  -" + myDataFlowToolWrapper + "\n" +
                                           "   -Test\n" +
                                           "    Identical qualifier and argument to 'equals()' call\n" +
                                           "   -Test2\n" +
                                           "    Identical qualifier and argument to 'equals()' call\n"
                                           + "  -" + myUnusedToolWrapper + "\n"
                                           + "   -Test\n"
                                           + "    " + varMessage("j") + "\n"
                                           + "    " + varMessage("test") + "\n"
                                           + "    " + varMessage("r") + "\n"
                                           + "    " + varMessage("i") + "\n"
                                           + "    " + varMessage("d") + "\n"
                                           + "    " + varMessage("a") + "\n");
    TreeUtil.selectFirstNode(tree);
    final InspectionTreeNode root = (InspectionTreeNode)tree.getLastSelectedPathComponent();
    root.excludeElement(myView.getExcludedManager());
    TreeUtil.traverse(root, new TreeUtil.Traverse() {
      @Override
      public boolean accept(final Object node) {
        assertTrue(((InspectionTreeNode)node).isExcluded(myView.getExcludedManager()));
        return true;
      }
    });
    myView.getGlobalInspectionContext().getUIOptions().FILTER_RESOLVED_ITEMS = true;
    tree = updateTree();
    PlatformTestUtil.assertTreeEqual(tree, getProject() + "\n");
    myView.getGlobalInspectionContext().getUIOptions().FILTER_RESOLVED_ITEMS = false;
    tree = updateTree();
    PlatformTestUtil.assertTreeEqual(tree, "-" + getProject() + "\n"
                                           + " -Probable bugs\n"
                                           + "  -" + myDataFlowToolWrapper + "\n" +
                                           "   -Test\n" +
                                           "    Identical qualifier and argument to 'equals()' call\n" +
                                           "   -Test2\n" +
                                           "    Identical qualifier and argument to 'equals()' call\n"
                                           + "  -" + myUnusedToolWrapper + "\n"
                                           + "   -Test\n"
                                           + "    " + varMessage("j") + "\n"
                                           + "    " + varMessage("test") + "\n"
                                           + "    " + varMessage("r") + "\n"
                                           + "    " + varMessage("i") + "\n"
                                           + "    " + varMessage("d") + "\n"
                                           + "    " + varMessage("a") + "\n");
  }

  private InspectionTree updateTree() {
    myView.update();
    final InspectionTree tree = myView.getTree();
    TreeUtil.expandAll(tree);
    return tree;
  }

  @Override
  protected String getTestPath() {
    return "inspection/offline";
  }

  @Override
  protected String getTestDirectoryName() {
    return "project";
  }
}
