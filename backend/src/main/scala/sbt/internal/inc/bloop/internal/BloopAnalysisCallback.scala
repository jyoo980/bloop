package sbt.internal.inc.bloop.internal

import java.io.File
import java.nio.file.Path
import java.{util => ju}

import sbt.internal.inc.Analysis
import sbt.internal.inc.Compilation
import sbt.internal.inc.Incremental
import sbt.internal.inc.PlainVirtualFileConverter
import sbt.internal.inc.SourceInfos
import sbt.internal.inc.UsedName
import sbt.internal.inc.UsedNames
import sbt.util.InterfaceUtil
import xsbt.api.APIUtil
import xsbt.api.HashAPI
import xsbt.api.NameHashing
import xsbti.Position
import xsbti.Problem
import xsbti.Severity
import xsbti.T2
import xsbti.UseScope
import xsbti.VirtualFile
import xsbti.VirtualFileRef
import xsbti.api.AnalyzedClass
import xsbti.api.ClassLike
import xsbti.api.Companions
import xsbti.api.DefinitionType
import xsbti.api.DependencyContext
import xsbti.api.ExternalDependency
import xsbti.api.InternalDependency
import xsbti.api.NameHash
import xsbti.api.SafeLazyProxy
import xsbti.compile.ClassFileManager
import xsbti.compile.IncOptions
import xsbti.compile.Output
import xsbti.compile.analysis.ReadStamps

trait IBloopAnalysisCallback extends xsbti.AnalysisCallback {
  def get: Analysis
}

final class BloopAnalysisCallback(
    internalBinaryToSourceClassName: String => Option[String],
    externalAPI: (Path, String) => Option[AnalyzedClass],
    stampReader: ReadStamps,
    output: Output,
    options: IncOptions,
    manager: ClassFileManager
) extends IBloopAnalysisCallback {

  private[this] val compilation: Compilation = Compilation(System.currentTimeMillis(), output)

  override def toString: String =
    (List("Class APIs", "Object APIs", "Binary deps", "Products", "Source deps") zip
      List(classApis, objectApis, binaryDeps, nonLocalClasses, intSrcDeps))
      .map { case (label, map) => label + "\n\t" + map.mkString("\n\t") }
      .mkString("\n")

  case class ApiInfo(
      publicHash: HashAPI.Hash,
      extraHash: HashAPI.Hash,
      classLike: ClassLike
  )

  import collection.mutable

  private[this] val srcs = mutable.HashSet[Path]()
  private[this] val classApis = new mutable.HashMap[String, ApiInfo]
  private[this] val objectApis = new mutable.HashMap[String, ApiInfo]
  private[this] val classPublicNameHashes = new mutable.HashMap[String, Array[NameHash]]
  private[this] val objectPublicNameHashes = new mutable.HashMap[String, Array[NameHash]]
  private[this] val usedNames = new mutable.HashMap[String, mutable.HashSet[UsedName]]
  private[this] val unreportedProblems = new mutable.HashMap[Path, mutable.ListBuffer[Problem]]
  private[this] val reportedProblems = new mutable.HashMap[Path, mutable.ListBuffer[Problem]]
  private[this] val mainClasses = new mutable.HashMap[Path, mutable.ListBuffer[String]]
  private[this] val binaryDeps = new mutable.HashMap[Path, mutable.HashSet[Path]]

  // source file to set of generated (class file, binary class name); only non local classes are stored here
  private[this] val nonLocalClasses = new mutable.HashMap[Path, mutable.HashSet[(Path, String)]]
  private[this] val localClasses = new mutable.HashMap[Path, mutable.HashSet[Path]]
  // mapping between src class name and binary (flat) class name for classes generated from src file
  private[this] val classNames = new mutable.HashMap[Path, mutable.HashSet[(String, String)]]
  // generated class file to its source class name
  private[this] val classToSource = new mutable.HashMap[Path, String]
  // internal source dependencies
  private[this] val intSrcDeps = new mutable.HashMap[String, mutable.HashSet[InternalDependency]]
  // external source dependencies
  private[this] val extSrcDeps = new mutable.HashMap[String, mutable.HashSet[ExternalDependency]]
  private[this] val binaryClassName = new mutable.HashMap[Path, String]
  // source files containing a macro def.
  private[this] val macroClasses = mutable.HashSet[String]()

  private[this] val converter = PlainVirtualFileConverter.converter

  private def add[A, B](map: mutable.HashMap[A, mutable.HashSet[B]], a: A, b: B): Unit = {
    map.getOrElseUpdate(a, new mutable.HashSet[B]()).+=(b)
    ()
  }

  def startSource(source: VirtualFile): Unit = {
    val sourcePath = converter.toPath(source)
    if (options.strictMode()) {
      assert(
        !srcs.contains(sourcePath),
        s"The startSource can be called only once per source file: $source"
      )
    }
    srcs.add(sourcePath)
    ()

  }

  def startSource(source: File): Unit = {
    startSource(converter.toVirtualFile(source.toPath()))
  }

  def problem(
      category: String,
      pos: Position,
      msg: String,
      severity: Severity,
      reported: Boolean
  ): Unit = {
    for (source <- InterfaceUtil.jo2o(pos.sourceFile)) {
      val map = if (reported) reportedProblems else unreportedProblems
      map
        .getOrElseUpdate(source.toPath(), new mutable.ListBuffer())
        .+=(InterfaceUtil.problem(category, pos, msg, severity, None, None, Nil))
    }
  }

  def classDependency(
      onClassName: String,
      sourceClassName: String,
      context: DependencyContext
  ): Unit = {
    if (onClassName != sourceClassName)
      add(intSrcDeps, sourceClassName, InternalDependency.of(sourceClassName, onClassName, context))
  }

  private[this] def externalBinaryDependency(
      binary: Path,
      className: String,
      source: VirtualFileRef
  ): Unit = {
    binaryClassName.put(binary, className)
    add(binaryDeps, converter.toPath(source), binary)
  }

  private[this] def externalSourceDependency(
      sourceClassName: String,
      targetBinaryClassName: String,
      targetClass: AnalyzedClass,
      context: DependencyContext
  ): Unit = {
    val dependency =
      ExternalDependency.of(sourceClassName, targetBinaryClassName, targetClass, context)
    add(extSrcDeps, sourceClassName, dependency)
  }

  override def binaryDependency(
      classFile: Path,
      onBinaryClassName: String,
      fromClassName: String,
      fromSourceFile: VirtualFileRef,
      context: DependencyContext
  ): Unit = {
    internalBinaryToSourceClassName(onBinaryClassName) match {
      case Some(dependsOn) => // dependsOn is a source class name
        // dependency is a product of a source not included in this compilation
        classDependency(dependsOn, fromClassName, context)
      case None =>
        classToSource.get(classFile) match {
          case Some(dependsOn) =>
            // dependency is a product of a source in this compilation step,
            //  but not in the same compiler run (as in javac v. scalac)
            classDependency(dependsOn, fromClassName, context)
          case None =>
            externalDependency(classFile, onBinaryClassName, fromClassName, fromSourceFile, context)
        }
    }
  }
  def binaryDependency(
      classFile: File,
      onBinaryClassName: String,
      fromClassName: String,
      fromSourceFile: File,
      context: DependencyContext
  ): Unit = {
    binaryDependency(
      classFile.toPath(),
      onBinaryClassName,
      fromClassName,
      converter.toVirtualFile(fromSourceFile.toPath()),
      context
    )
  }

  private[this] def externalDependency(
      classFile: Path,
      onBinaryName: String,
      sourceClassName: String,
      sourceFile: VirtualFileRef,
      context: DependencyContext
  ): Unit = {
    externalAPI(classFile, onBinaryName) match {
      case Some(api) =>
        // dependency is a product of a source in another project
        val targetBinaryClassName = onBinaryName
        externalSourceDependency(sourceClassName, targetBinaryClassName, api, context)
      case None =>
        // dependency is some other binary on the classpath
        val name = classFile.toString()
        // avoid binary deps which are not one of those
        if (name.endsWith(".class") || name.endsWith(".jar"))
          externalBinaryDependency(classFile, onBinaryName, sourceFile)
    }
  }

  override def generatedNonLocalClass(
      source: VirtualFileRef,
      classFile: Path,
      binaryClassName: String,
      srcClassName: String
  ): Unit = {
    val sourcePath = converter.toPath(source)
    add(nonLocalClasses, sourcePath, (classFile, binaryClassName))
    add(classNames, sourcePath, (srcClassName, binaryClassName))
    classToSource.put(classFile, srcClassName)
    ()

  }

  def generatedNonLocalClass(
      source: File,
      classFile: File,
      binaryClassName: String,
      srcClassName: String
  ): Unit = {
    generatedNonLocalClass(
      converter.toVirtualFile(source.toPath()),
      classFile.toPath(),
      binaryClassName,
      srcClassName
    )
  }

  override def generatedLocalClass(source: VirtualFileRef, classFile: Path): Unit = {
    add(localClasses, converter.toPath(source), classFile)
    ()
  }

  def generatedLocalClass(source: File, classFile: File): Unit = {
    generatedLocalClass(converter.toVirtualFile(source.toPath()), classFile.toPath())
  }

  def api(sourceFile: VirtualFileRef, classApi: ClassLike): Unit = {
    import xsbt.api.{APIUtil, HashAPI}
    val className = classApi.name
    if (APIUtil.isScalaSourceName(sourceFile.name()) && APIUtil.hasMacro(classApi))
      macroClasses.add(className)
    val shouldMinimize = !Incremental.apiDebug(options)
    val savedClassApi = if (shouldMinimize) APIUtil.minimize(classApi) else classApi
    val apiHash: HashAPI.Hash = HashAPI(classApi)
    val nameHashes = (new xsbt.api.NameHashing(options.useOptimizedSealed())).nameHashes(classApi)
    classApi.definitionType match {
      case d @ (DefinitionType.ClassDef | DefinitionType.Trait) =>
        val extraApiHash = {
          if (d != DefinitionType.Trait) apiHash
          else HashAPI(_.hashAPI(classApi), includePrivateDefsInTrait = true)
        }

        classApis(className) = ApiInfo(apiHash, extraApiHash, savedClassApi)
        classPublicNameHashes(className) = nameHashes
      case DefinitionType.Module | DefinitionType.PackageModule =>
        objectApis(className) = ApiInfo(apiHash, apiHash, savedClassApi)
        objectPublicNameHashes(className) = nameHashes
    }
  }

  override def api(sourceFile: File, classApi: ClassLike): Unit = {
    api(converter.toVirtualFile(sourceFile.toPath()), classApi)
  }

  override def mainClass(sourceFile: VirtualFileRef, className: String): Unit = {
    mainClasses.getOrElseUpdate(converter.toPath(sourceFile), new mutable.ListBuffer).+=(className)
    ()
  }

  def mainClass(sourceFile: File, className: String): Unit = {
    mainClass(converter.toVirtualFile(sourceFile.toPath()), className)
  }

  def usedName(className: String, name: String, useScopes: ju.EnumSet[UseScope]): Unit =
    add(usedNames, className, UsedName(name, useScopes))

  override def enabled(): Boolean = options.enabled

  override def get: Analysis = {
    addUsedNames(addCompilation(addProductsAndDeps(Analysis.empty)))
  }

  // According to docs this is used for build tools and it's not unused in Bloop
  override def isPickleJava(): Boolean = false
  override def getPickleJarPair(): ju.Optional[T2[Path, Path]] = ju.Optional.empty()

  def getOrNil[A, B](m: collection.Map[A, Seq[B]], a: A): Seq[B] = m.get(a).toList.flatten
  def addCompilation(base: Analysis): Analysis =
    base.copy(compilations = base.compilations.add(compilation))
  def addUsedNames(base: Analysis): Analysis = usedNames.foldLeft(base) {
    case (a, (className, names)) =>
      a.copy(relations = a.relations.addUsedNames(UsedNames.fromMultiMap(Map(className -> names))))
  }

  private def companionsWithHash(className: String): (Companions, HashAPI.Hash, HashAPI.Hash) = {
    val emptyHash = -1
    val emptyClass =
      ApiInfo(emptyHash, emptyHash, APIUtil.emptyClassLike(className, DefinitionType.ClassDef))
    val emptyObject =
      ApiInfo(emptyHash, emptyHash, APIUtil.emptyClassLike(className, DefinitionType.Module))
    val ApiInfo(classApiHash, classHashExtra, classApi) = classApis.getOrElse(className, emptyClass)
    val ApiInfo(objectApiHash, objectHashExtra, objectApi) =
      objectApis.getOrElse(className, emptyObject)
    val companions = Companions.of(classApi, objectApi)
    val apiHash = (classApiHash, objectApiHash).hashCode
    val extraHash = (classHashExtra, objectHashExtra).hashCode
    (companions, apiHash, extraHash)
  }

  private def nameHashesForCompanions(className: String): Array[NameHash] = {
    val classNameHashes = classPublicNameHashes.get(className)
    val objectNameHashes = objectPublicNameHashes.get(className)
    (classNameHashes, objectNameHashes) match {
      case (Some(nm1), Some(nm2)) => NameHashing.merge(nm1, nm2)
      case (Some(nm), None) => nm
      case (None, Some(nm)) => nm
      case (None, None) => sys.error("Failed to find name hashes for " + className)
    }
  }

  private def analyzeClass(name: String): AnalyzedClass = {
    val hasMacro: Boolean = macroClasses.contains(name)
    val (companions, apiHash, extraHash) = companionsWithHash(name)
    val nameHashes = nameHashesForCompanions(name)
    val safeCompanions = SafeLazyProxy(companions)
    AnalyzedClass.of(
      compilation.getStartTime(),
      name,
      safeCompanions,
      apiHash,
      nameHashes,
      hasMacro,
      extraHash
    )
  }

  def addProductsAndDeps(base: Analysis): Analysis = {
    srcs.foldLeft(base) {
      case (a, src) =>
        val stamp = stampReader.source(converter.toVirtualFile(src))
        val classesInSrc =
          classNames.getOrElse(src, new mutable.HashSet[(String, String)]()).map(_._1)
        val analyzedApis = classesInSrc.map(analyzeClass)
        val info = SourceInfos.makeInfo(
          getOrNil(reportedProblems, src),
          getOrNil(unreportedProblems, src),
          getOrNil(mainClasses, src)
        )
        val binaries = binaryDeps.getOrElse(src, Nil: Iterable[Path])
        val localProds = localClasses
          .getOrElse(src, new mutable.HashSet[Path]())
          .map { classFile =>
            val virtualFile = converter.toVirtualFile(classFile)
            val classFileStamp = stampReader.product(virtualFile)
            Analysis.LocalProduct(virtualFile, classFileStamp)
          }
        val binaryToSrcClassName =
          (classNames
            .getOrElse(src, new mutable.HashSet[(String, String)]())
            .map {
              case (srcClassName, binaryClassName) => (binaryClassName, srcClassName)
            })
            .toMap
        val nonLocalProds = nonLocalClasses
          .getOrElse(src, Nil: Iterable[(Path, String)])
          .map {
            case (classFile, binaryClassName) =>
              val virtualFile = converter.toVirtualFile(classFile)
              val srcClassName = binaryToSrcClassName(binaryClassName)
              val classFileStamp = stampReader.product(virtualFile)
              Analysis.NonLocalProduct(srcClassName, binaryClassName, virtualFile, classFileStamp)
          }

        val internalDeps = classesInSrc.flatMap(cls =>
          intSrcDeps.getOrElse(cls, new mutable.HashSet[InternalDependency]())
        )
        val externalDeps = classesInSrc.flatMap(cls =>
          extSrcDeps.getOrElse(cls, new mutable.HashSet[ExternalDependency]())
        )
        val binDeps = binaries.map { d =>
          val virtual = converter.toVirtualFile(d)
          (virtual, binaryClassName(d), stampReader.library(virtual))
        }

        a.addSource(
          converter.toVirtualFile(src),
          analyzedApis,
          stamp,
          info,
          nonLocalProds,
          localProds,
          internalDeps,
          externalDeps,
          binDeps
        )
    }
  }

  override def apiPhaseCompleted(): Unit = {
    /*
     * Inform the class file manager of the generated Scala classes as soon as
     * the Zinc API phase has run and collected them so that the class file
     * invalidation registers these files before compiling Java files incrementally.
     */
    manager.generated(classToSource.keysIterator.map(converter.toVirtualFile).toArray)
  }
  override def dependencyPhaseCompleted(): Unit = ()
  override def classesInOutputJar(): java.util.Set[String] = ju.Collections.emptySet()

}
