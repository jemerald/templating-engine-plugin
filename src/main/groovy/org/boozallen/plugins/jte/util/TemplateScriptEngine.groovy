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
package org.boozallen.plugins.jte.util

import java.lang.reflect.Field
import org.boozallen.plugins.jte.init.primitives.TemplateBinding
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.ImportCustomizer
import org.jenkinsci.plugins.workflow.cps.CpsThreadGroup
import java.lang.LinkageError
import org.jenkinsci.plugins.workflow.cps.CpsGroovyShellFactory

/*
    We do a lot of executing the code inside files and JTE is
    dependent on the binding being preserved.

    The default CpsGroovyShell leveraged in CpsScript's evaluate method
    is insufficient for our needs because it instantiates each shell with
    a new Binding() instead of using getBinding().

    </rant>
*/
class TemplateScriptEngine implements Serializable{

    static GroovyShell createShell(){
        return new CpsGroovyShellFactory(null).forTrusted().build()
    }

    static Script parse(String scriptText, Binding b){
        String fileName = "jte" + System.currentTimeMillis() + Math.abs(scriptText.hashCode()) + ".groovy"
        Script script = createShell().getClassLoader().parseClass(scriptText).newInstance()
        script.setBinding(b)
        return script
    }

    static Class parseClass(String classText){
        GroovyClassLoader classLoader = createShell().getClassLoader()
        GroovyClassLoader tempLoader = new GroovyClassLoader(classLoader)
        /*
            Creating a new, short-lived class loader that inherits the
            compiler configuration of the pipeline's is the easiest
            way to parse a file and see if the class has been loaded
            before
        */
        Class clazz = tempLoader.parseClass(classText)
        Class returnClass = clazz
        if(classLoader.getClassCacheEntry(clazz.getName())){
            // class has been loaded before. fetch and return
            returnClass = classLoader.loadClass(clazz.getName())
        } else {
            // class has not be parsed before, add to the runs class loader
            classLoader.setClassCacheEntry(returnClass)
        }
        return returnClass
    }
}
