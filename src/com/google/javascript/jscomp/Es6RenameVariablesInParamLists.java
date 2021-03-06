/*
 * Copyright 2014 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp;

import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.rhino.Node;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Renames declarations and references in function bodies to avoid shadowing
 * names referenced in the parameter list, in default values or computed properties.
 *
 * @author moz@google.com (Michael Zhou)
 */
public final class Es6RenameVariablesInParamLists extends AbstractPostOrderCallback
    implements HotSwapCompilerPass {

  private final AbstractCompiler compiler;

  public Es6RenameVariablesInParamLists(AbstractCompiler compiler) {
    this.compiler = compiler;
  }

  @Override
  public void visit(NodeTraversal t, Node n, Node parent) {
    // Arrow functions without blocked body cannot have declarations in the body
    if (!n.isFunction() || !n.getLastChild().isBlock()) {
      return;
    }

    Node paramList = n.getSecondChild();
    final CollectReferences collector = new CollectReferences();
    NodeTraversal.traverseEs6(compiler, paramList, new NodeTraversal.AbstractPreOrderCallback() {
      @Override
      public final boolean shouldTraverse(NodeTraversal t, Node n, Node parent) {
        if (parent == null) {
          return true;
        }

        if (parent.isDefaultValue() && n == parent.getLastChild()
            || parent.isComputedProp() && n == parent.getFirstChild()) {
          NodeTraversal.traverseEs6(compiler, n, collector);
          return false;
        }
        return true;
      }
    });

    Node block = paramList.getNext();
    Es6SyntacticScopeCreator creator = new Es6SyntacticScopeCreator(compiler);
    Scope fScope = creator.createScope(n, t.getScope());
    Scope fBlockScope = creator.createScope(block, fScope);
    Map<String, String> currFuncRenameMap = new HashMap<>();
    for (Var var : fBlockScope.getVarIterable()) {
      String oldName = var.getName();
      if (collector.currFuncReferences.contains(oldName)
          && !currFuncRenameMap.containsKey(oldName)) {
        currFuncRenameMap.put(
            oldName, oldName + "$" + compiler.getUniqueNameIdSupplier().get());
      }
    }
    Map<Node, Map<String, String>> renameMap = new LinkedHashMap<>();
    renameMap.put(fBlockScope.rootNode, currFuncRenameMap);
    new NodeTraversal(compiler, new Es6RenameReferences(renameMap))
        .traverseInnerNode(block, block.getParent(), fScope);
  }

  @Override
  public void process(Node externs, Node root) {
    TranspilationPasses.processTranspile(compiler, root, this);
  }

  @Override
  public void hotSwapScript(Node scriptRoot, Node originalRoot) {
    TranspilationPasses.hotSwapTranspile(compiler, scriptRoot, this);
  }

  /**
   * Collects all references in a naive way.
   */
  private static class CollectReferences extends NodeTraversal.AbstractPostOrderCallback {

    private final Set<String> currFuncReferences = new HashSet<>();

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (!NodeUtil.isReferenceName(n)) {
        return;
      }
      currFuncReferences.add(n.getString());
    }
  }
}
