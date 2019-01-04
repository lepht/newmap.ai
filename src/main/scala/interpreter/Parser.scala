package ai.newmap.interpreter

import ai.newmap.model._
import scala.util.parsing.combinator.Parsers
import scala.util.parsing.input.{Reader, Position, NoPosition}
import ai.newmap.util.{Outcome, Success, Failure}

object NewMapParser extends Parsers {
  override type Elem = Lexer.Token

  class TokenReader(tokens: Seq[Lexer.Token]) extends Reader[Lexer.Token] {
    override def first: Lexer.Token = tokens.head
    override def atEnd: Boolean = tokens.isEmpty
    override def pos: Position = NoPosition
    override def rest: Reader[Lexer.Token] = new TokenReader(tokens.tail)
  }

  private def naturalNumber: Parser[NaturalNumberParse] = {
    accept("number", { case Lexer.Number(i) => {
      NaturalNumberParse(i)
    }})
  }

  private def identifier: Parser[IdentifierParse] = {
    accept("identifier", { case Lexer.Identifier(id) => {
      IdentifierParse(id)
    }})
  }

  private def forcedId: Parser[IdentifierParse] = {
    Lexer.Tilda() ~ identifier ^^ {
      case _ ~ IdentifierParse(s, _) => IdentifierParse(s, force = true)
    }
  }

  private def expressionInParens: Parser[ParseTree] = {
    Lexer.Enc(Paren, true) ~ expressionList ~ Lexer.Enc(Paren, false) ^^ {
      case _ ~ exps ~ _ => {
        exps
      }
    }
  }

  private def kvBinding: Parser[BindingCommandItem] = {
    val pattern = expressionList ~ Lexer.Colon() ~ expressionList
    pattern ^^ {
      case key ~ _ ~ value => {
        BindingCommandItem(key, value)
      }
    }
  }

  private def commandList: Parser[CommandList] = {
    val pattern = {
      Lexer.Enc(Paren, true) ~
        repsep(kvBinding | expressionList, Lexer.Comma()) ~
        Lexer.Enc(Paren, false)
    }
    pattern ^^ {
      case _ ~ items ~ _ => {
        CommandList(items.toVector)
      }
    }
  }

  private def lambdaParseInParens: Parser[LambdaParse] = {
    val pattern = {
      Lexer.Enc(Paren, true) ~
        lambdaParse ~
        Lexer.Enc(Paren, false)
    }
    pattern ^^ {
      case _ ~ lp ~ _ => lp
    }
  }

  private def lambdaParse: Parser[LambdaParse] = {
    expressionList ~ Lexer.Arrow() ~ expressionList ^^ {
      case input ~ _ ~ output => {
        LambdaParse(input, output)
      }
    }
  }

  private def expression: Parser[ParseTree] = {
    expressionInParens | naturalNumber | identifier | forcedId | lambdaParseInParens | commandList
  }

  private def expressionList: Parser[ParseTree] = {
    rep1(expression) ^^ {
      case exps => {
        // TODO - make this type safe
        if (exps.length > 1) ApplyParse(exps(0), exps.drop(1).toVector)
        else exps(0)
      }
    }
  }

  private def fullStatement: Parser[FullStatementParse] = {
    Lexer.Identifier("val") ~ identifier ~ Lexer.Colon() ~ expressionList ~ Lexer.Equals() ~ expressionList ^^ {
      case _ ~ id ~ _ ~ typeExp ~ _ ~ exp => {
        FullStatementParse(ValStatement, id, typeExp, exp)
      }
    }
  }

  private def inferredTypeStatement: Parser[InferredTypeStatementParse] = {
    Lexer.Identifier("val") ~ identifier ~ Lexer.Equals() ~ expressionList ^^ {
      case _ ~ id ~ _ ~ exp => {
        InferredTypeStatementParse(ValStatement, id, exp)
      }
    }
  }

  private def expOnlyStatmentParse: Parser[ExpressionOnlyStatementParse] = {
    expressionList ^^ {
      case exp => {
        ExpressionOnlyStatementParse(exp)
      }
    }
  }

  def apply(
    tokens: Seq[Lexer.Token]
  ): Outcome[ParseTree, String] = {
    val reader = new TokenReader(tokens)
    val program = phrase(expressionList)

    program(reader) match {
      case NoSuccess(msg, next) => ai.newmap.util.Failure(msg)
      case Success(result, next) => ai.newmap.util.Success(result)
    }
  }

  def statementParse(
    tokens: Seq[Lexer.Token]
  ): Outcome[EnvStatementParse, String] = {
    val reader = new TokenReader(tokens)
    val program = phrase(fullStatement | inferredTypeStatement | expOnlyStatmentParse)

    program(reader) match {
      case NoSuccess(msg, next) => ai.newmap.util.Failure(msg)
      case Success(result, next) => ai.newmap.util.Success(result)
    }
  }
}