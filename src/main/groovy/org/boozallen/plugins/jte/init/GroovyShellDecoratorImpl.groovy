/*
 Copyright 2018 Booz Allen Hamilton
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at
 http://www.apache.org/licenses/LICENSE-2.0
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
package org.boozallen.plugins.jte.init

import groovy.lang.GroovyShell
import hudson.Extension
import java.lang.reflect.Field
import javax.annotation.CheckForNull
import org.boozallen.plugins.jte.init.primitives.hooks.CleanUp
import org.boozallen.plugins.jte.init.primitives.hooks.HookInjector
import org.boozallen.plugins.jte.init.primitives.hooks.Init
import org.boozallen.plugins.jte.init.primitives.hooks.Notify
import org.boozallen.plugins.jte.init.primitives.hooks.Validate
import org.boozallen.plugins.jte.init.primitives.TemplateBinding
import org.boozallen.plugins.jte.job.TemplateFlowDefinition
import org.boozallen.plugins.jte.util.TemplateLogger
import org.codehaus.groovy.ast.builder.AstBuilder
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.GroovyCodeVisitor
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.classgen.GeneratorContext
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.CompilationCustomizer
import org.codehaus.groovy.control.customizers.ImportCustomizer
import org.codehaus.groovy.control.SourceUnit
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution
import org.jenkinsci.plugins.workflow.cps.CpsThread
import org.jenkinsci.plugins.workflow.cps.GroovyShellDecorator
import org.jenkinsci.plugins.workflow.flow.FlowDefinition
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.job.WorkflowRun


@Extension
public class GroovyShellDecoratorImpl extends GroovyShellDecorator {

    @Override
    void configureShell(@CheckForNull CpsFlowExecution context, GroovyShell shell) {
        if(!context){
            return
        }
        FlowExecutionOwner owner = context.getOwner()
        WorkflowRun run = owner.run()
        PipelineDecorator pipelineDecorator = run.getAction(PipelineDecorator)
        if(pipelineDecorator){
            TemplateBinding binding = pipelineDecorator.getBinding()
            Field shellBinding = GroovyShell.class.getDeclaredField("context")
            shellBinding.setAccessible(true)
            shellBinding.set(shell, binding)
        }
    }

    @Override
    public GroovyShellDecorator forTrusted() {
        return this
    }

    @Override
    public void configureCompiler(@CheckForNull final CpsFlowExecution execution, CompilerConfiguration cc) {
        /*
         add a star import for the hooks package so that library developers
         do not have to import Lifecyle Hooks to reference them
        */
        ImportCustomizer ic = new ImportCustomizer()
        ic.addStarImports("org.boozallen.plugins.jte.init.primitives.hooks")
        cc.addCompilationCustomizers(ic)

        if(isFromJTE(execution)){
            cc.addCompilationCustomizers(new CompilationCustomizer(CompilePhase.SEMANTIC_ANALYSIS){
                @Override
                void call(SourceUnit source, GeneratorContext context, ClassNode classNode) throws CompilationFailedException {
                    if(!isTemplate(classNode)){
                        return
                    }
                    ModuleNode template = source.getAST()
                    List<Statement> statements = template.getStatementBlock().getStatements()

                    /*
                        TODO:
                        The references to Hook, Validate, Init, CleanUp, and Notify
                        are actually coming from HookInjector.
                        Need to come back to this and figure out how to use AstBuilder
                        to reference classes.  Right now, the AST getting produces is
                        trying to find those names as variables in the binding.
                        So i put them in the binding for now </shrug>
                    */
                    BlockStatement wrapper = (new AstBuilder().buildFromCode(CompilePhase.SEMANTIC_ANALYSIS){
                        try{
                            Hooks.invoke(Validate, this.getBinding())
                            Hooks.invoke(Init, this.getBinding())
                            // <-- this is where the template AST statements get injected
                        }finally{
                            Hooks.invoke(CleanUp, this.getBinding())
                            Hooks.invoke(Notify, this.getBinding())
                        }
                    }).first()

                    wrapper.getStatements().first().getTryStatement().addStatements(statements)

                    statements.clear()
                    statements.add(0, wrapper)
                }

                /*
                    need to ensure we're only modifying AST for the template.
                    an example of additional scripts getting compiled would be from a
                    template or library step that does something like:
                    evaluate("x = 1")

                    to understand why specifically checking for "WorkflowScript" see:
                    https://github.com/jenkinsci/workflow-cps-plugin/blob/workflow-cps-2.80/src/main/java/org/jenkinsci/plugins/workflow/cps/CpsFlowExecution.java#L561
                    
                    to understand where "evaluate" method comes from see:
                    https://github.com/jenkinsci/workflow-cps-plugin/blob/workflow-cps-2.80/src/main/java/org/jenkinsci/plugins/workflow/cps/CpsScript.java#L178-L182
                 */
                boolean isTemplate(ClassNode classNode){
                    return classNode.getName().equals("WorkflowScript")
                }
            })
        }
    }

    boolean isFromJTE(CpsFlowExecution execution){
        if(!execution){
            return false // no execution defined yet, still initializing
        }

        WorkflowJob job = execution.getOwner().run().getParent()
        FlowDefinition definition = job.getDefinition()
        if(!(definition in TemplateFlowDefinition)){
            return false // not a JTE pipeline
        }

        return true
    }
}
