package io.kaitai.struct.languages

import io.kaitai.struct.datatype.DataType._
import io.kaitai.struct.datatype._
import io.kaitai.struct.exprlang.Ast
import io.kaitai.struct.format._
import io.kaitai.struct.languages.components._
import io.kaitai.struct.translators.{GoTranslator, ResultString, TranslatorResult}
import io.kaitai.struct.{ClassTypeProvider, RuntimeConfig, Utils}

class GoCompiler(typeProvider: ClassTypeProvider, config: RuntimeConfig)
  extends LanguageCompiler(typeProvider, config)
    with SingleOutputFile
    with UpperCamelCaseClasses
    with ObjectOrientedLanguage
    with UniversalFooter
    with UniversalDoc
    with AllocateIOLocalVar
    with GoReads {
  import GoCompiler._

  override val translator = new GoTranslator(out, typeProvider, importList)

  override def innerClasses = false

  override def universalFooter: Unit = {
    out.dec
    out.puts("}")
  }

  override def indent: String = "\t"
  override def outFileName(topClassName: String): String =
    s"src/${config.goPackage}/$topClassName.go"

  override def outImports(topClass: ClassSpec): String = {
    val imp = importList.toList
    imp.size match {
      case 0 => ""
      case 1 => "import \"" + imp.head + "\"\n"
      case _ =>
        "import (\n" +
        imp.map((x) => indent + "\"" + x + "\"").mkString("", "\n", "\n") +
        ")\n"
    }
  }

  override def fileHeader(topClassName: String): Unit = {
    outHeader.puts(s"// $headerComment")
    if (config.goPackage.nonEmpty) {
      outHeader.puts
      outHeader.puts(s"package ${config.goPackage}")
    }
    outHeader.puts

    importList.add("github.com/kaitai-io/kaitai_struct_go_runtime/kaitai")

    out.puts
  }

  override def classHeader(name: List[String]): Unit = {
    out.puts(s"type ${types2class(name)} struct {")
    out.inc
  }

  override def classFooter(name: List[String]): Unit = {
    // TODO(jchw): where should this attribute actually be generated at?
    typeProvider.nowClass.meta.endian match {
      case Some(_: CalcEndian) | Some(InheritedEndian) =>
        out.puts(s"${idToStr(EndianIdentifier)} int")
      case _ =>
    }
    universalFooter
  }

  override def classConstructorHeader(name: List[String], parentType: DataType, rootClassName: List[String], isHybrid: Boolean, params: List[ParamDefSpec]): Unit = {}

  override def classConstructorFooter: Unit = {}

  override def runRead(): Unit = {
    out.puts("this.Read()")
  }

  override def runReadCalc(): Unit = {
    out.puts
    out.puts(s"switch ${privateMemberName(EndianIdentifier)} {")
    out.puts("case 0:")
    out.inc
    out.puts("err = this._read_be()")
    out.dec
    out.puts("case 1:")
    out.inc
    out.puts("err = this._read_le()")
    out.dec
    out.puts("default:")
    out.inc
    out.puts(s"err = ${GoCompiler.ksErrorName(UndecidedEndiannessError)}{}")
    out.dec
    out.puts("}")
  }

  override def readHeader(endian: Option[FixedEndian], isEmpty: Boolean): Unit = {
    endian match {
      case None =>
        out.puts
        out.puts(
          s"func (this *${types2class(typeProvider.nowClass.name)}) Read(" +
            s"io *$kstreamName, " +
            s"parent ${kaitaiType2NativeType(typeProvider.nowClass.parentType)}, " +
            s"root *${types2class(typeProvider.topClass.name)}) (err error) {"
        )
        out.inc
        out.puts(s"${privateMemberName(IoIdentifier)} = io")
        out.puts(s"${privateMemberName(ParentIdentifier)} = parent")
        out.puts(s"${privateMemberName(RootIdentifier)} = root")
        typeProvider.nowClass.meta.endian match {
          case Some(_: CalcEndian) =>
            out.puts(s"${privateMemberName(EndianIdentifier)} = -1")
          case Some(InheritedEndian) =>
            out.puts(s"${privateMemberName(EndianIdentifier)} = " +
              s"${privateMemberName(ParentIdentifier)}." +
              s"${idToStr(EndianIdentifier)}")
          case _ =>
        }
        out.puts
      case Some(e) =>
        out.puts
        out.puts(
          s"func (this *${types2class(typeProvider.nowClass.name)}) " +
            s"_read_${e.toSuffix}() (err error) {")
        out.inc
    }

  }
  override def readFooter(): Unit = {
    out.puts("return err")
    universalFooter
  }

  override def attributeDeclaration(attrName: Identifier, attrType: DataType, isNullable: Boolean): Unit = {
    out.puts(s"${idToStr(attrName)} ${kaitaiType2NativeType(attrType)}")
    translator.returnRes = None
  }

  override def attributeReader(attrName: Identifier, attrType: DataType, isNullable: Boolean): Unit = {}

  override def universalDoc(doc: DocSpec): Unit = {
    out.puts
    out.puts( "/**")

    doc.summary.foreach(summary => out.putsLines(" * ", summary))

    doc.ref.foreach {
      case TextRef(text) =>
        out.putsLines(" * ", "@see \"" + text + "\"")
      case ref: UrlRef =>
        out.putsLines(" * ", s"@see ${ref.toAhref}")
    }

    out.puts( " */")
  }

  override def attrParseHybrid(leProc: () => Unit, beProc: () => Unit): Unit = {
    out.puts(s"switch ${privateMemberName(EndianIdentifier)} {")
    out.puts("case 0:")
    out.inc
    beProc()
    out.dec
    out.puts("case 1:")
    out.inc
    leProc()
    out.dec
    out.puts("default:")
    out.inc
    out.puts(s"err = ${GoCompiler.ksErrorName(UndecidedEndiannessError)}{}")
    out.dec
    out.puts("}")
  }

  override def attrFixedContentsParse(attrName: Identifier, contents: Array[Byte]): Unit = {
    out.puts(s"${privateMemberName(attrName)}, err = $normalIO.ReadBytes(${contents.length})")

    out.puts(s"if err != nil {")
    out.inc
    out.puts("return err")
    out.dec
    out.puts("}")

    importList.add("bytes")
    importList.add("errors")
    val expected = translator.resToStr(translator.doByteArrayLiteral(contents))
    out.puts(s"if !bytes.Equal(${privateMemberName(attrName)}, $expected) {")
    out.inc
    out.puts("return errors.New(\"Unexpected fixed contents\")")
    out.dec
    out.puts("}")
  }

  override def attrProcess(proc: ProcessExpr, varSrc: Identifier, varDest: Identifier): Unit = {
    val srcName = privateMemberName(varSrc)
    val destName = privateMemberName(varDest)

    proc match {
      case ProcessXor(xorValue) =>
        translator.detectType(xorValue) match {
          case _: IntType =>
            out.puts(s"$destName = kaitai.ProcessXOR($srcName, []byte{${expression(xorValue)}})")
          case _: BytesType =>
            out.puts(s"$destName = kaitai.ProcessXOR($srcName, ${expression(xorValue)})")
        }
      case ProcessZlib =>
        out.puts(s"$destName, err = kaitai.ProcessZlib($srcName)")
        translator.outAddErrCheck()
      case ProcessRotate(isLeft, rotValue) =>
        val expr = if (isLeft) {
          expression(rotValue)
        } else {
          s"8 - (${expression(rotValue)})"
        }
        out.puts(s"$destName = kaitai.ProcessRotateLeft($srcName, int($expr))")
      case ProcessCustom(name, args) =>
        // TODO(jchw): This hack is necessary because Go tests fail catastrophically otherwise...
        out.puts(s"$destName = $srcName")
    }
  }

  override def allocateIO(varName: Identifier, rep: RepeatSpec): String = {
    val javaName = privateMemberName(varName)

    val ioName = idToStr(IoStorageIdentifier(varName))

    val args = rep match {
      case RepeatEos | RepeatExpr(_) => s"$javaName[len($javaName) - 1]"
      case RepeatUntil(_) => translator.specialName(Identifier.ITERATOR2)
      case NoRepeat => javaName
    }

    importList.add("bytes")

    out.puts(s"$ioName := kaitai.NewStream(bytes.NewReader($args))")
    ioName
  }

  override def useIO(ioEx: Ast.expr): String = {
    out.puts(s"thisIo := ${expression(ioEx)}")
    "thisIo"
  }

  override def pushPos(io: String): Unit = {
    out.puts(s"_pos, err := $io.Pos()")
    translator.outAddErrCheck()
  }

  override def seek(io: String, pos: Ast.expr): Unit = {
    importList.add("io")

    out.puts(s"_, err = $io.Seek(int64(${expression(pos)}), io.SeekStart)")
    translator.outAddErrCheck()
  }

  override def popPos(io: String): Unit = {
    importList.add("io")

    out.puts(s"_, err = $io.Seek(_pos, io.SeekStart)")
    translator.outAddErrCheck()
  }

  override def alignToByte(io: String): Unit =
    out.puts(s"$io.AlignToByte()")

  override def condIfHeader(expr: Ast.expr): Unit = {
    out.puts(s"if (${expression(expr)}) {")
    out.inc
  }

  override def condRepeatEosHeader(id: Identifier, io: String, dataType: DataType, needRaw: Boolean): Unit = {
    if (needRaw)
      out.puts(s"${privateMemberName(RawIdentifier(id))} = make([][]byte, 0);")
    //out.puts(s"${privateMemberName(id)} = make(${kaitaiType2NativeType(ArrayType(dataType))})")
    out.puts(s"for {")
    out.inc

    val eofVar = translator.allocateLocalVar()
    out.puts(s"${translator.localVarName(eofVar)}, err := this._io.EOF()")
    translator.outAddErrCheck()
    out.puts(s"if ${translator.localVarName(eofVar)} {")
    out.inc
    out.puts("break")
    out.dec
    out.puts("}")
  }

  override def handleAssignmentRepeatEos(id: Identifier, r: TranslatorResult): Unit = {
    val name = privateMemberName(id)
    val expr = translator.resToStr(r)
    out.puts(s"$name = append($name, $expr)")
  }

  override def condRepeatExprHeader(id: Identifier, io: String, dataType: DataType, needRaw: Boolean, repeatExpr: Ast.expr): Unit = {
    if (needRaw)
      out.puts(s"${privateMemberName(RawIdentifier(id))} = make([][]byte, ${expression(repeatExpr)})")
    out.puts(s"${privateMemberName(id)} = make(${kaitaiType2NativeType(ArrayType(dataType))}, ${expression(repeatExpr)})")
    out.puts(s"for i := range ${privateMemberName(id)} {")
    out.inc
  }

  override def handleAssignmentRepeatExpr(id: Identifier, r: TranslatorResult): Unit = {
    val name = privateMemberName(id)
    val expr = translator.resToStr(r)
    out.puts(s"$name[i] = $expr")
  }

  override def condRepeatUntilHeader(id: Identifier, io: String, dataType: DataType, needRaw: Boolean, untilExpr: Ast.expr): Unit = {
    if (needRaw)
      out.puts(s"${privateMemberName(RawIdentifier(id))} = make([][]byte, 0);")
    out.puts("for {")
    out.inc
  }

  override def handleAssignmentRepeatUntil(id: Identifier, r: TranslatorResult, isRaw: Boolean): Unit = {
    val expr = translator.resToStr(r)
    val tempVar = translator.specialName(Identifier.ITERATOR)
    out.puts(s"$tempVar := $expr")
    out.puts(s"${privateMemberName(id)} = append(${privateMemberName(id)}, $tempVar)")
  }

  override def condRepeatUntilFooter(id: Identifier, io: String, dataType: DataType, needRaw: Boolean, untilExpr: Ast.expr): Unit = {
    typeProvider._currentIteratorType = Some(dataType)
    out.puts(s"if ${expression(untilExpr)} {")
    out.inc
    out.puts("break")
    out.dec
    out.puts("}")
    out.dec
    out.puts("}")
  }

  private def castToType(r: TranslatorResult, dataType: DataType): TranslatorResult = {
    dataType match {
      case t @ (_: IntMultiType | _: FloatMultiType) =>
        ResultString(s"${kaitaiType2NativeType(t)}(${translator.resToStr(r)})")
      case _ =>
        r
    }
  }

  private def combinedType(dataType: DataType) = {
    dataType match {
      case st: SwitchType => st.combinedType
      case _ => dataType
    }
  }

  private def handleCompositeTypeCast(id: Identifier, r: TranslatorResult): TranslatorResult = {
    id match {
      case NamedIdentifier(name) =>
        castToType(r, combinedType(typeProvider.determineType(name)))
      case _ =>
        r
    }
  }

  override def handleAssignmentSimple(id: Identifier, r: TranslatorResult): Unit = {
    val expr = translator.resToStr(handleCompositeTypeCast(id, r))
    out.puts(s"${privateMemberName(id)} = $expr")
  }

  override def parseExpr(dataType: DataType, io: String, defEndian: Option[FixedEndian]): String = {
    dataType match {
      case t: ReadableType =>
        s"$io.Read${Utils.capitalize(t.apiCall(defEndian))}()"
      case blt: BytesLimitType =>
        s"$io.ReadBytes(int(${expression(blt.size)}))"
      case _: BytesEosType =>
        s"$io.ReadBytesFull()"
      case BytesTerminatedType(terminator, include, consume, eosError, _) =>
        s"$io.ReadBytesTerm($terminator, $include, $consume, $eosError)"
      case BitsType1 =>
        s"$io.ReadBitsInt(1)"
      case BitsType(width: Int) =>
        s"$io.ReadBitsInt($width)"
      case t: UserType =>
        val addArgs = if (t.isOpaque) {
          ""
        } else {
          val parent = t.forcedParent match {
            case Some(USER_TYPE_NO_PARENT) => "null"
            case Some(fp) => translator.translate(fp)
            case None => "this"
          }
          s", $parent, _root"
        }
        s"${types2class(t.name)}($io$addArgs)"
    }
  }

//  override def bytesPadTermExpr(expr0: String, padRight: Option[Int], terminator: Option[Int], include: Boolean) = {
//    val expr1 = padRight match {
//      case Some(padByte) => s"$kstreamName.bytesStripRight($expr0, (byte) $padByte)"
//      case None => expr0
//    }
//    val expr2 = terminator match {
//      case Some(term) => s"$kstreamName.bytesTerminate($expr1, (byte) $term, $include)"
//      case None => expr1
//    }
//    expr2
//  }

  override def switchStart(id: Identifier, on: Ast.expr): Unit = {
    out.puts(s"switch (${expression(on)}) {")
  }

  override def switchCaseStart(condition: Ast.expr): Unit = {
    out.puts(s"case ${expression(condition)}:")
    out.inc
  }

  override def switchCaseEnd(): Unit = {
    out.dec
  }

  override def switchElseStart(): Unit = {
    out.puts("default:")
    out.inc
  }

  override def switchEnd(): Unit =
    out.puts("}")

  override def switchShouldUseCompareFn(onType: DataType): Option[String] = {
    onType match {
      case _: BytesType =>
        importList.add("bytes")
        Some("bytes.Equal")
      case _ =>
        None
    }
  }

  override def switchCaseStartCompareFn(compareFn: String, switchOn: Ast.expr, condition: Ast.expr): Unit = {
    out.puts(s"case ${compareFn}(${expression(switchOn)}, ${expression(condition)}):")
    out.inc
  }

  override def instanceDeclaration(attrName: InstanceIdentifier, attrType: DataType, isNullable: Boolean): Unit = {
    out.puts(s"${calculatedFlagForName(attrName)} bool")
    out.puts(s"${idToStr(attrName)} ${kaitaiType2NativeType(attrType)}")
  }

  override def instanceHeader(className: List[String], instName: InstanceIdentifier, dataType: DataType, isNullable: Boolean): Unit = {
    out.puts(s"func (this *${types2class(className)}) ${publicMemberName(instName)}() (v ${kaitaiType2NativeType(dataType)}, err error) {")
    out.inc
    translator.returnRes = Some(dataType match {
      case _: IntType => "0"
      case _: BooleanType => "false"
      case _: StrType => "\"\""
      case _ => "nil"
    })
  }

  override def instanceCalculate(instName: Identifier, dataType: DataType, value: Ast.expr): Unit = {
    val r = translator.translate(value)
    val converted = dataType match {
      case _: UserType => r
      case _ => s"${kaitaiType2NativeType(dataType)}($r)"
    }
    out.puts(s"${privateMemberName(instName)} = $converted")
  }

  override def instanceCheckCacheAndReturn(instName: InstanceIdentifier, dataType: DataType): Unit = {
    out.puts(s"if (this.${calculatedFlagForName(instName)}) {")
    out.inc
    instanceReturn(instName, dataType)
    universalFooter
  }

  override def instanceReturn(instName: InstanceIdentifier, attrType: DataType): Unit = {
    out.puts(s"return ${privateMemberName(instName)}, nil")
  }

  override def instanceSetCalculated(instName: InstanceIdentifier): Unit =
    out.puts(s"this.${calculatedFlagForName(instName)} = true")

  override def enumDeclaration(curClass: List[String], enumName: String, enumColl: Seq[(Long, EnumValueSpec)]): Unit = {
    val fullEnumName: List[String] = curClass ++ List(enumName)
    val fullEnumNameStr = types2class(fullEnumName)

    out.puts
    out.puts(s"type $fullEnumNameStr int")
    out.puts("const (")
    out.inc

    enumColl.foreach { case (id, label) =>
      out.puts(s"${enumToStr(fullEnumName, label.name)} $fullEnumNameStr = $id")
    }

    out.dec
    out.puts(")")
  }

  def idToStr(id: Identifier): String = {
    id match {
      case SpecialIdentifier(name) => name
      case NamedIdentifier(name) => Utils.upperCamelCase(name)
      case NumberedIdentifier(idx) => s"_${NumberedIdentifier.TEMPLATE}$idx"
      case InstanceIdentifier(name) => Utils.lowerCamelCase(name)
      case RawIdentifier(innerId) => "_raw_" + idToStr(innerId)
      case IoStorageIdentifier(innerId) => "_io_" + idToStr(innerId)
    }
  }

  override def privateMemberName(id: Identifier): String = s"this.${idToStr(id)}"

  override def publicMemberName(id: Identifier): String = {
    id match {
      case IoIdentifier => "_IO"
      case RootIdentifier => "_Root"
      case ParentIdentifier => "_Parent"
      case NamedIdentifier(name) => Utils.upperCamelCase(name)
      case NumberedIdentifier(idx) => s"_${NumberedIdentifier.TEMPLATE}$idx"
      case InstanceIdentifier(name) => Utils.upperCamelCase(name)
      case RawIdentifier(innerId) => "_raw_" + idToStr(innerId)
    }
  }

  override def localTemporaryName(id: Identifier): String = s"_t_${idToStr(id)}"

  def calculatedFlagForName(id: Identifier) = s"_f_${idToStr(id)}"

  override def ksErrorName(err: KSError): String = GoCompiler.ksErrorName(err)
}

object GoCompiler extends LanguageCompilerStatic
  with UpperCamelCaseClasses
  with StreamStructNames
  with ExceptionNames {

  override def getCompiler(
    tp: ClassTypeProvider,
    config: RuntimeConfig
  ): LanguageCompiler = new GoCompiler(tp, config)

  /**
    * Determine Go data type corresponding to a KS data type.
    *
    * @param attrType KS data type
    * @return Go data type
    */
  def kaitaiType2NativeType(attrType: DataType): String = {
    attrType match {
      case Int1Type(false) => "uint8"
      case IntMultiType(false, Width2, _) => "uint16"
      case IntMultiType(false, Width4, _) => "uint32"
      case IntMultiType(false, Width8, _) => "uint64"

      case Int1Type(true) => "int8"
      case IntMultiType(true, Width2, _) => "int16"
      case IntMultiType(true, Width4, _) => "int32"
      case IntMultiType(true, Width8, _) => "int64"

      case FloatMultiType(Width4, _) => "float32"
      case FloatMultiType(Width8, _) => "float64"

      case BitsType(_) => "uint64"

      case _: BooleanType => "bool"
      case CalcIntType => "int"
      case CalcFloatType => "float64"

      case _: StrType => "string"
      case _: BytesType => "[]byte"

      case AnyType => "interface{}"
      case KaitaiStreamType => "*" + kstreamName
      case KaitaiStructType | CalcKaitaiStructType => kstructName

      case t: UserType => "*" + types2class(t.classSpec match {
        case Some(cs) => cs.name
        case None => t.name
      })
      case t: EnumType => types2class(t.enumSpec.get.name)

      case ArrayType(inType) => s"[]${kaitaiType2NativeType(inType)}"

      case st: SwitchType => kaitaiType2NativeType(st.combinedType)
    }
  }

  def types2class(names: List[String]): String = names.map(x => type2class(x)).mkString("_")

  def enumToStr(enumTypeAbs: List[String]): String = {
    val enumName = enumTypeAbs.last
    val enumClass: List[String] = enumTypeAbs.dropRight(1)
    enumToStr(enumClass, enumName)
  }

  def enumToStr(typeName: List[String], enumName: String): String =
    types2class(typeName) + "__" + type2class(enumName)

  override def kstreamName: String = "kaitai.Stream"
  override def kstructName: String = "interface{}"
  override def ksErrorName(err: KSError): String = err match {
    case EndOfStreamError => "kaitai.EndOfStreamError"
    case UndecidedEndiannessError => "kaitai.UndecidedEndiannessError"
    case ValidationNotEqualError => "kaitai.ValidationNotEqualError"
  }
}
