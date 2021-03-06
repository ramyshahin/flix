/*
 * Copyright 2017 Magnus Madsen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ca.uwaterloo.flix.language.phase.jvm

import java.nio.file.{Path, Paths}

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.CompilationError
import ca.uwaterloo.flix.language.ast.FinalAst._
import ca.uwaterloo.flix.language.ast.Symbol
import ca.uwaterloo.flix.language.phase.Phase
import ca.uwaterloo.flix.runtime.solver.api.ProxyObject
import ca.uwaterloo.flix.runtime.{CompilationResult, Linker}
import ca.uwaterloo.flix.util.Validation._
import ca.uwaterloo.flix.util.{Evaluation, Validation}

object JvmBackend extends Phase[Root, CompilationResult] {

  // TODO: Ramin/Magnus: Can and should we make he backend completely independent from flix.api?
  // Then we would need to generate CellX, Unit, and a collection of exceptions.

  /**
    * The directory where to place the generated class files.
    */
  val TargetDirectory: Path = Paths.get("./target/flix/")

  /**
    * Emits JVM bytecode for the given AST `root`.
    */
  def run(root: Root)(implicit flix: Flix): Validation[CompilationResult, CompilationError] = flix.phase("JvmBackend") {
    //
    // Immediately return if in interpreted mode.
    //
    if (flix.options.evaluation == Evaluation.Interpreted) {
      val defs = root.defs.foldLeft(Map.empty[Symbol.DefnSym, () => ProxyObject]) {
        case (macc, (sym, defn)) =>
          // Invokes the function with a single argument (which is supposed to be the Unit value, but we pass null instead).
          val args: Array[AnyRef] = Array(null)
          macc + (sym -> (() => Linker.link(sym, root).invoke(args)))
      }
      return new CompilationResult(root, defs).toSuccess
    }

    //
    // Immediately return if in verification mode.
    //
    if (flix.options.verifier) {
      return new CompilationResult(root, Map.empty).toSuccess
    }

    //
    // Put the AST root into implicit scope.
    //
    implicit val _ = root

    //
    // Compute the set of closures in the program.
    //
    val closures = JvmOps.closuresOf(root)

    //
    // Compute the set of namespaces in the program.
    //
    val namespaces = JvmOps.namespacesOf(root)

    //
    // Compute the set of instantiated tags in the program.
    //
    val tags = JvmOps.tagsOf(root)

    //
    // Compute the set of fusion tags in the program.
    //
    val fusionTags = tags.flatMap(JvmOps.getFusionTag)

    //
    // Compute the set of types in the program.
    //
    val types = JvmOps.typesOf(root)

    //
    // Generate the main class.
    //
    val mainClass = GenMainClass.gen()

    //
    // Generate the Context class.
    //
    val contextClass = GenContext.gen(namespaces)

    //
    // Generate the namespace classes.
    //
    val namespaceClasses = GenNamespaces.gen(namespaces)

    //
    // Generate continuation interfaces for each function type in the program.
    //
    val continuationInterfaces = GenContinuationInterfaces.gen(types)

    //
    // Generate function interfaces for each function type in the program.
    //
    val functionInterfaces = GenFunctionInterfaces.gen(types)

    //
    // Generate function classes for each function in the program.
    //
    val functionClasses = GenFunctionClasses.gen(root.defs)

    //
    // Generate closure classes for each closure in the program.
    //
    val closureClasses = GenClosureClasses.gen(closures)

    //
    // Generate enum interfaces for each enum type in the program.
    //
    val enumInterfaces = GenEnumInterfaces.gen(types)

    //
    // Generate tag classes for each enum instantiation in the program.
    //
    val tagClasses = GenTagClasses.gen(tags)

    //
    // Generate tuple interfaces for each tuple type in the program.
    //
    val tupleInterfaces = GenTupleInterfaces.gen(types)

    //
    // Generate tuple classes for each tuple type in the program.
    //
    val tupleClasses = GenTupleClasses.gen(types)

    //
    // Generate tag-tuple fusion classes for tag-tuple in the program.
    //
    val fusionClasses = GenFusionClasses.gen(fusionTags)

    //
    // Generate cell classes.
    //
    val cellClasses = GenCellClasses.gen()

    //
    // Collect all the classes and interfaces together.
    //
    val allClasses = List(
      mainClass,
      contextClass,
      namespaceClasses,
      continuationInterfaces,
      functionInterfaces,
      functionClasses,
      closureClasses,
      enumInterfaces,
      tagClasses,
      tupleInterfaces,
      tupleClasses,
      fusionClasses,
      cellClasses
    ).reduce(_ ++ _)

    //
    // Write each class (and interface) to disk.
    //
    // NB: In interactive and test mode we skip writing the files to disk.
    if (flix.options.writeClassFiles && !flix.options.test) {
      flix.subphase("WriteClasses") {
        for ((jvmName, jvmClass) <- allClasses) {
          JvmOps.writeClass(TargetDirectory, jvmClass)
        }
      }
    }

    //
    // Loads all the generated classes into the JVM and decorates the AST.
    //
    Bootstrap.bootstrap(allClasses)

    //
    // Construct a map from symbols to actual JVM code.
    //
    val defs = root.defs.foldLeft(Map.empty[Symbol.DefnSym, () => ProxyObject]) {
      case (macc, (sym, defn)) =>
        // Invokes the function with a single argument (which is supposed to be the Unit value, but we pass null instead).
        val args: Array[AnyRef] = Array(null)
        macc + (sym -> (() => Linker.link(sym, root).invoke(args)))
    }

    //
    // Return the compilation result.
    //
    new CompilationResult(root, defs).toSuccess
  }

}
