package ai.newmap.interpreter

import ai.newmap.model._
import ai.newmap.util.{Outcome, Success, Failure}

// Handles type classes, and their composition
object TypeClassUtils {
  def typeClassIsExpectingAnIdentifier(
    nType: NewMapType,
    s: String,
    env: Environment
  ): Outcome[NewMapType, String] = nType match {
    case IdentifierT => Success(nType)
    //case CustomT(_, t) => typeIsExpectingAnIdentifier(t, s, env)
    case SubtypeT(isMember, parentType, _) => {
      for {
        _ <- typeClassIsExpectingAnIdentifier(parentType, s, env)
        membershipCheck <- Evaluator.applyFunctionAttempt(isMember, UIdentifier(s), env)
        _ <- Outcome.failWhen(membershipCheck == UInit, s"Value $s not a member of subtype $nType")
      } yield nType
    }
    case WildcardPatternT(_) => {
      // TODO - really we should be returning the typeclass of all types that accept identifiers
      Success(IdentifierT)
    }
    case _ => {
      Failure(s"Type couldn't accept identifier $s as value: $nType")
    }
  }

  def typeIsExpectingAnIndex(
    nType: NewMapType,
    i: Long,
    env: Environment
  ): Outcome[Unit, String] = nType match {
    case TypeT => Success()
    case HistoricalTypeT(_) => Success()
    case CountT => Success()
    case BooleanT => Outcome.failWhen(i > 1, s"Input to Boolean must be 0 or 1.. was $i")
    case IndexT(j) => Outcome.failWhen(i >= j, s"Proposed index $i is too large for type $j")
    case CustomT(name, params) => {
      val typeSystem = env.typeSystem
      val currentState = typeSystem.currentState

      for {
        currentMapping <- Outcome(typeSystem.historicalMapping.get(currentState), s"Current type mapping $currentState not found")
        currentTypeId <- Outcome(currentMapping.get(name), s"$name must be defined")
        currentUnderlyingType <- Outcome(typeSystem.typeToUnderlyingType.get(currentTypeId), s"Couldn't find underlying type for $name")

        currentParameterPattern = currentUnderlyingType._1
        currentUnderlyingExp = currentUnderlyingType._2

        underlyingT <- typeSystem.convertToNewMapType(currentUnderlyingExp)

        //TODO: The env should. be updated with currentParameterPattern
        result <- typeIsExpectingAnIndex(underlyingT, i, env)
      } yield result
      
    }
    case SubtypeT(isMember, parentType, _) => {
      for {
        _ <- typeIsExpectingAnIndex(parentType, i, env)
        membershipCheck <- Evaluator.applyFunctionAttempt(isMember, UIndex(i), env)
        _ <- Outcome.failWhen(membershipCheck == UInit, s"Value $i not a member of subtype $nType")
      } yield ()
    }
    case StructT(params, _, _, _) => {
      if (params.length == 1) {
        for {
          typeObj <- Evaluator(params.head._2, env)
          t <- Evaluator.asType(typeObj, env)
          result <- typeIsExpectingAnIndex(t, i, env)
        } yield result
      } else {
        Failure(s"Struct Type couldn't accept index $i as value: $nType")
      }
    }
    case _ => {
      Failure(s"Type couldn't accept index $i as value: $nType")
    }
  }

  // Return conversion instructions
  def isTypeConvertibleToPattern(
    startingType: NewMapType,
    endingTypePattern: NewMapType,
    env: Environment
  ): Outcome[Vector[UntaggedObject], String] = {
    endingTypePattern match {
      case WildcardPatternT(_) => {
        Success(Vector.empty)
      }
      case endingType => {
        SubtypeUtils.isTypeConvertible(startingType, endingType, env)
      }
    }
  }
}