package ai.newmap.interpreter

import ai.newmap.interpreter.parser.{NewMapCodeParser, NewMapParser}
import ai.newmap.model._
import ai.newmap.util.{Outcome, Success, Failure}

import java.io.File
import scala.io.Source

class EnvironmentInterpreter(
  printInitCommandErrors: Boolean = true,
  suppressStdout: Boolean = false,
) {
  var env: Environment = Environment.Base

  if (suppressStdout) env = env.copy(printStdout = false)

  val loadFileErrors = loadFile("system/init.nm")
  if (printInitCommandErrors) {
    println(loadFileErrors)
  }

  /*
   * @param code The code entered into the interpreter
   * @return The response from the interpreter
   */
  def apply(code: String): Outcome[String, String] = {
    applyInterpCommand(code) match {
      case CommandPrintSomething(response) => Success(response)
      case CommandExit => Success(":exit")
      case CommandPassThrough => applyEnvCommand(code)
    }
  }

  abstract class CommandInterpResponse
  case class CommandPrintSomething(s: String) extends CommandInterpResponse
  case object CommandExit extends CommandInterpResponse
  case object CommandPassThrough extends CommandInterpResponse

  val Pattern = "(:parse )(.*)".r

  def applyInterpCommand(code: String): CommandInterpResponse = {
    code match {
      case ":env" => CommandPrintSomething(env.toString)
      case ":types" => CommandPrintSomething(env.printTypes)
      case ":channels" => CommandPrintSomething(env.printChannels)
      case ":exit" | ":quit" => CommandExit
      case _ if (code.startsWith(":parse ")) => {
        CommandPrintSomething(formatStatementParserCode(code.drop(7)))
      }
      case _ if (code.startsWith(":tokenize ")) => {
        CommandPrintSomething(formatStatementLexerCode(code.drop(10)))
      }
      case _ if (code.startsWith(":underlyingType ")) => {
        CommandPrintSomething(evaluateAndUnderlying(code.drop(16)))
      }
      case _ if (code.startsWith(":load ")) => {
        CommandPrintSomething(loadFile(code.drop(6)))
      }
      case _ if (code.startsWith(":ls ")) => {
        CommandPrintSomething(printDirectory(code.drop(4)))
      }
      case _ if (code.startsWith(":typeOf ")) => {
        CommandPrintSomething(getTypeOf(code.drop(8)))
      }
      case ":uuid" => CommandPrintSomething(java.util.UUID.randomUUID.toString)
      case ":help" => CommandPrintSomething(
        "List of environment commands\n" ++
        ":env\tPrint the current environment\n" ++
        ":types\tPrint the types in the current environment\n" ++
        ":parse [Expression]\tPrint how [Expression] is parsed and type checked\n" ++
        ":tokenize [Expression]\tPrint how [Expression] is Tokenized by running the lexer\n" ++
        ":typeOf [Expression]\tPrint out the type of the expression\n" ++
        ":channels\tPrint the channels in this environment\n" ++
        ":exit | :quit\tExit this repl\n" ++
        ":help\tPrint this help message\n"
      )
      case _ => CommandPassThrough
    }
  }

  private def getTokens(code: String): Outcome[List[Lexer.Token], String] = {
    if (code.isEmpty) Success(List.empty)
    else Lexer(code)
  }

  private def getTokensWithNewline(code: String): Outcome[List[Lexer.Token], String] = {
    getTokens(code).map(tokens => tokens :+ Lexer.NewLine())
  }

  private def getTypeOf(code: String): String = {
    val result = for {
      tokens <- getTokens(code)
      parseTree <- NewMapParser.expressionParse(tokens)
      tc <- TypeChecker.typeCheckUnknownType(parseTree, env, Map.empty)
      nObject <- Evaluator(tc.nExpression, env)
    } yield {
      tc.refinedTypeClass
    }

    result match {
      case Success(s) => s.displayString(env)
      case Failure(reason) => reason
    }
  }

  private def evaluateAndUnderlying(code: String): String = {
    val result = for {
      tokens <- getTokens(code)
      parseTree <- NewMapParser.expressionParse(tokens)
      tc <- TypeChecker.typeCheck(parseTree, TypeT, env, FullFunction, Map.empty)
      nObject <- Evaluator(tc.nExpression, env)
      nType <- env.typeSystem.convertToNewMapType(nObject)
      underlyingType <- TypeChecker.getFinalUnderlyingType(nType, env, env.typeSystem.currentState)
    } yield {
      underlyingType.displayString(env)
    }

    result match {
      case Success(s) => s
      case Failure(reason) => reason
    }
  }

  private def formatStatementParserCode(code: String): String = {
    statementParser(code) match {
      case Success(p) => p.toString
      case Failure(s) => s"Parse Failed: $s"
    }
  }

  private def formatStatementLexerCode(code: String): String = {
    getTokens(code) match {
      case Success(p) => p.mkString("\t")
      case Failure(s) => s"Tokenizer Failed: $s"
    }
  }

  def loadFile(filename: String): String = {
    val baseDir = "src/main/newmap/"

    val fileName = baseDir + filename

    val linesIt = Source.fromFile(fileName).getLines

    loadFileFromIterator(linesIt, env) match {
      case Success(newEnv) => {
        env = newEnv
        ""
      }
      case Failure(reason) => s"Failure: $reason"
    }
  }

  private def loadFileFromIterator(
    linesIt: Iterator[String],
    env: Environment,
    tokens: Seq[Lexer.Token] = Seq.empty,
    parser: NewMapCodeParser = NewMapCodeParser()
  ): Outcome[Environment, String] = {
    var tokens: Seq[Lexer.Token] = Vector.empty
    var parser: NewMapCodeParser = NewMapCodeParser()
    var mutableEnv = env

    def hasMoreTokens: Boolean = {
      tokens.length > 0 || linesIt.hasNext
    }

    def getNextToken: Lexer.Token = tokens match {
      case firstToken +: otherTokens => {
        tokens = otherTokens
        firstToken
      }
      case _ if (linesIt.hasNext) => {
        val code = linesIt.next()
        getTokensWithNewline(code).map(ts => {
          tokens = ts
        })
        getNextToken        
      }
      case _ => throw new Exception("Error: loadFileFromIterator failed")
    }

    while(hasMoreTokens) {
      parser.update(getNextToken) match {
        case Success(response) => {
          parser = response.newParser

          response.statementOutput.map(statement => {
            val result = for {
              interpreted <- StatementInterpreter(statement, mutableEnv, Map.empty)
              command <- StatementEvaluator(interpreted.command, mutableEnv)
            } yield {
              mutableEnv = mutableEnv.newCommand(command)
            }

            result match {
              case Failure(f) => return Failure(f)
              case _ => ()
            }
          })
        }
        case Failure(f) => return Failure(f)
      }
    }

    Success(mutableEnv)
  }

  private def printDirectory(dir: String): String = {
    val d = new File(dir)
    val files = if (d.exists && d.isDirectory) {
      d.listFiles.filter(_.isFile).toList
    } else List[File]()
    
    s"directory: $files"
  }

  private def statementParser(code: String): Outcome[EnvStatementParse, String] = {
    for {
      tokens <- getTokens(code)
      statementParse <- NewMapParser.statementParse(tokens)
    } yield statementParse
  }

  def applyEnvCommand(code: String): Outcome[String, String] = {
    for {
      tokens <- getTokens(code)

      // TODO: This statement parse can now be "waiting" for the next line.. keep that in mind
      statementParse <- NewMapParser.statementParse(tokens)

      interpreted <- StatementInterpreter(statementParse, env, Map.empty)
      command <- StatementEvaluator(interpreted.command, env)
    } yield {
      applyEnvCommand(command)
    }
  }

  // Directly apply an environment comment
  def applyEnvCommand(command: EnvironmentCommand): String = {
    env = env.newCommand(command)
    command.displayString(env)
  }
}