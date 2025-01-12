package ai.newmap.interpreter

import ai.newmap.interpreter.TypeChecker.{patternToExpression, typeCheck, typeCheckGenericMap}
import ai.newmap.model._
import ai.newmap.util.{Failure, Outcome, Success}

object StatementEvaluator {
  /*
   * @param sParse The statement parses
   * @param env This is a map of identifiers which at this point are supposed to be subsituted.
   * @return Evaluate the environment command so that it's ready to be executed
   */
  def apply(
    command: EnvironmentCommand,
    env: Environment
  ): Outcome[EnvironmentCommand, String] = {
    command match {
      case c@FullEnvironmentCommand(id, nExpression, isDef) => {
        for {
          evaluatedObject <- Evaluator(nExpression.uObject, env)
          constantObject = Evaluator.stripVersioningU(evaluatedObject, env)
          nObject <- TypeChecker.tagAndNormalizeObject(constantObject, nExpression.nType, env)
        } yield {
          c.copy(nObject = nObject)
        }
      }
      case c@ApplyIndividualCommand(id, command) => {
        for {
          evaluatedCommand <- Evaluator(command, env)
          constantCommand = Evaluator.stripVersioningU(evaluatedCommand, env)
        } yield {
          c.copy(nObject = constantCommand)
        }
      }
      case ExpOnlyEnvironmentCommand(exp) => {
        for {
          evaluatedObject <- Evaluator(exp.uObject, env)
          constantObject = Evaluator.stripVersioningU(evaluatedObject, env)
          nObject <- TypeChecker.tagAndNormalizeObject(constantObject, exp.nType, env)
        } yield {
          ExpOnlyEnvironmentCommand(nObject)
        }
      }
     case _ => Success(command)
    }
  }
}