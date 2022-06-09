package ai.newmap.interpreter

import ai.newmap.model._
import ai.newmap.util.{Outcome, Success, Failure}
import java.util.UUID

// Evaluates an expression that's already been type checked
object Evaluator {
  def apply(
    nExpression: UntaggedObject,
    env: Environment
  ): Outcome[UntaggedObject, String] = {
    nExpression match {
      case ApplyFunction(func, input) => {
        for {
          evalFunc <- this(func, env)
          evalInput <- this(input, env)
          result <- applyFunctionAttempt(evalFunc, evalInput, env)
        } yield {
          result
        }
      }
      case ParamId(s) => {
        env.lookup(s) match {
          case None => {
            //throw new Exception(s"Unbound identifier: $s")
            Failure(s"Unbound identifier: $s")
          }
          case Some(EnvironmentBinding(nObject)) => removeTypeTag(nObject)
          case Some(EnvironmentParameter(nObject)) => {
            Success(ParamId(s))
            //throw new Exception(s"Cannot evaluate identifier $s, since it is an unbound parameter of type $nObject")
            //Failure(s"Cannot evaluate identifier $s, since it is an unbound parameter of type $nObject")
          }
        }
      }
      case UCase(constructor, input) => {
        for {
          evalInput <- this(input, env)
        } yield {
          UCase(constructor, evalInput)
        }
      }
      case UMap(values) => {
        for {
          evalValues <- evalMapInstanceVals(values, env)
        } yield UMap(evalValues)
      }
      case UStruct(values) => {
        for {
          evalValues <- evalStructVals(values, env)
        } yield UStruct(evalValues)
      }
      case constant => Success(constant)
    }
  }

  // TODO - try to remove the usage of this as much as possible
  // (In other words, look for ways to rely on the type less)
  def removeTypeTag(nObject: NewMapObject): Outcome[UntaggedObject, String] = {
    nObject match {
      case TaggedObject(uObject, _) => Success(uObject)
      case VersionedObjectLink(key) => Success(ULink(key))
      case _ => {
        //throw new Exception(nObject.toString)
        Failure(s"Can't yet remove type tag from typed object $nObject (once types are redefined as a case it'll be possible)")
      }
    }
  }

  def Index(i: Long): NewMapObject = TaggedObject(UIndex(i), CountT)

  def lookupVersionedObject(
    id: String,
    env: Environment
  ): Outcome[VersionedObjectLink, String] = {
    for {
      versionedObject <- Outcome(env.lookup(id), s"Identifier $id not found!")

      versionedO <- versionedObject match {
        case EnvironmentBinding(vo@VersionedObjectLink(_)) => Success(vo)
        case EnvironmentBinding(nObject) => Failure(s"Identifier $id does not point to a versioned object. It is actually ${nObject}.")
        case EnvironmentParameter(_) => Failure(s"Identifier $id is a parameter, should be an object")
      }
    } yield versionedO
  }

  def latestVersion(uuid: UUID, env: Environment): Outcome[Long, String] = {
    env.latestVersionNumber.get(uuid) match {
      case Some(v) => Success(v)
      case None => Failure(s"Couldn't find version number for $uuid")
    }
  }

  def indicatedState(key: VersionedObjectKey, env: Environment): Outcome[NewMapObject, String] = {
    for {
      currentState <- env.storedVersionedTypes.get(key) match {
        case Some(obj) => Success(obj)
        case None => Failure(s"Couldn't find current state of version ${key.versionNumber} number for ${key.uuid}")
      }
    } yield currentState
  }

  def currentState(uuid: UUID, env: Environment): Outcome[NewMapObject, String] = {
    for {
      v <- latestVersion(uuid, env)
      currentState <- indicatedState(VersionedObjectKey(v, uuid), env)
    } yield currentState
  }

  def evalMapInstanceVals(
    values: Vector[(UntaggedObject, UntaggedObject)],
    env: Environment
  ): Outcome[Vector[(UntaggedObject, UntaggedObject)], String] = {
    values match {
      case (k, v) +: restOfValues => {
        for {
          evalRest <- evalMapInstanceVals(restOfValues, env)
        } yield {
          // TODO - rethink about "when to evaluate" what's inside a map
          // - We probably should not evaluate if it's not a simple function
          /*val newV = Evaluator(v, env) match {
            case Success(vObj) => vObj
            case _ => v
          }*/
          val newV = v

          (k -> newV) +: evalRest
        }
      }
      case _ => Success(Vector.empty)
    }
  }

  def evalStructVals(
    values: Vector[UntaggedObject],
    env: Environment
  ): Outcome[Vector[UntaggedObject], String] = {
    values match {
      case v +: restOfValues => {
        for {
          evalRest <- evalStructVals(restOfValues, env)
          evalV <- this(v, env)
        } yield {
          evalV +: evalRest
        }
      }
      case _ => Success(Vector.empty)
    }
  }

  def expressionListToObjects(
    nExpressions: Vector[UntaggedObject],
    env: Environment
  ): Outcome[Vector[UntaggedObject], String] = {
    nExpressions match {
      case nExpression +: tailExpressions => {
        for {
          nObject <- this(nExpression, env)
          restOfObjects <- expressionListToObjects(tailExpressions, env)
        } yield nObject +: restOfObjects
      }
      case _ => Success(Vector.empty)
    }
  }


  // Assume that both the function and the input have been evaluated
  def applyFunctionAttempt(
    func: UntaggedObject,
    input: UntaggedObject,
    env: Environment
  ): Outcome[UntaggedObject, String] = {
    stripVersioningU(func, env) match {
      case UMap(values) => {
        val keyMatchResult = attemptPatternMatchInOrder(values, input, env) match {
          case Success(result) => result
          case Failure(_) => {
            // Because this is already type checked, we can infer that MapCompleteness == CommandOutput
            // - If it had equaled "MapCompleteness", then we shouldn't be in a situation with no match
            UInit
          }
        }

        this(keyMatchResult, env)
      }
      case IsCommandFunc => {
        val defaultValueOutcome = for {
          inputT <- env.typeSystem.convertToNewMapType(input)
          defaultValue <- CommandMaps.getDefaultValueOfCommandType(inputT, env)
        } yield defaultValue

        val isCommand: Boolean = defaultValueOutcome.isSuccess

        Success(if (isCommand) UIndex(1) else UInit)
      }
      case _ => {
        //throw new Exception(s"Not implemented: apply function\nFunction: $func\nInput: $input")
        Failure(s"Not implemented: apply function\nFunction: $func\nInput: $input")
      }
    }
  }

  def attemptPatternMatchInOrder(
    remainingPatterns: Vector[(UntaggedObject, UntaggedObject)],
    input: UntaggedObject,
    env: Environment
  ): Outcome[UntaggedObject, String] = {
    remainingPatterns match {
      case (pattern, answer) +: addlPatterns => {
        attemptPatternMatch(pattern, input, env) match {
          case Success(paramsToSubsitute) => {
            Success(MakeSubstitution(answer, paramsToSubsitute))
          }
          case Failure(_) => attemptPatternMatchInOrder(addlPatterns, input, env)
        }
      }
      case _ => Failure(
        s"Unable to pattern match $input, The type checker should have caught this so there may be an error in there",
      )
    }
  }

  def attemptPatternMatch(
    pattern: UntaggedObject,
    input: UntaggedObject,
    env: Environment
  ): Outcome[Map[String, UntaggedObject], String] = {
    // TODO: IMPORTANT
    // We must be able to deal with using the same variable in a pattern, like StructPattern(x, x) to
    //  denote that these are the same
    (pattern, stripVersioningU(input, env)) match {
      case (UStruct(params), UMap(paramValues)) => {
        for {
          inputs <- expressionListToObjects(paramValues.map(_._2), env)
          result <- patternMatchOnStruct(params, inputs, env)
        } yield result 
      }
      case (UStruct(params), UStruct(inputParams)) => {
        patternMatchOnStruct(params, inputParams, env)
      }
      case (UStruct(params), singleValue) if (params.length == 1) => {
        attemptPatternMatch(params.head, singleValue, env)
      }
      case (UWildcardPattern(name), _) => {
        Success(Map(name -> input))
      }
      case (UCase(constructorP, inputP), UCase(constructor, cInput)) => {
        for {
          _ <- Outcome.failWhen(attemptPatternMatch(constructorP, constructor, env).isFailure, "Constructors didn't match")
          result <- attemptPatternMatch(inputP, cInput, env)
        } yield result
      }
      case (UCase(UIdentifier("Inc"), i), UIndex(j)) if j > 0 => {
        attemptPatternMatch(i, UIndex(j - 1), env)
      }
      case (_, UWildcardPattern(wildcard)) => {
        Failure("Failed Pattern Match: Split wildcard $wildcard on $pattern")
      }
      case (oPattern, strippedInput) => {
        // TODO - instead of checking for equality here - go through each untagged object configuration
        if (oPattern == input) {
          Success(Map.empty)
        } else {
          Failure("Failed Pattern Match")
        }
      }
    }
  }

  def patternMatchOnStruct(
    structPattern: Vector[UntaggedObject],
    inputs: Vector[UntaggedObject],
    env: Environment
  ): Outcome[Map[String, UntaggedObject], String] = {
    (structPattern, inputs) match {
      case (firstPattern +: restOfPatterns, firstInput +: restOfInputs) => {
        for {
          newParameters <- attemptPatternMatch(firstPattern, firstInput, env)
          otherParameters <- patternMatchOnStruct(restOfPatterns, restOfInputs, env)
        } yield {
          newParameters ++ otherParameters
        }
      }
      case _ if (structPattern.isEmpty && inputs.isEmpty) => Success(Map.empty) // No patterns to match
      case _ => {
        Failure(s"patternMatchOnStruct: structPattern and inputPattern are of different length. Leftovers: $structPattern -- $inputs")
      }  
    }
  }

  // TODO - move this elsewhere, maybe to environment!
  def newParametersFromPattern(pattern: UntaggedObject): Vector[String] = pattern match {
    case UWildcardPattern(name) => Vector(name)
    case UStruct(patterns) => patterns match {
      case firstPattern +: otherPatterns => {
        newParametersFromPattern(firstPattern) ++ newParametersFromPattern(UStruct(otherPatterns))
      }
      case _ => Vector.empty
    }
    case UCase(constructor, input) => {
      newParametersFromPattern(input)
    }
    case UMap(values) => newParametersFromPattern(UStruct(values.map(_._2)))
    case _ => Vector.empty
  }

  def stripVersioning(nObject: NewMapObject, env: Environment): NewMapObject = {
    nObject match {
      case VersionedObjectLink(key) => {
        // TODO - make this function an outcome
        currentState(key.uuid, env).toOption.get
      }
      case _ => nObject
    }
  }

  def stripVersioningU(uObject: UntaggedObject, env: Environment): UntaggedObject = {
    uObject match {
      case ULink(key) => removeTypeTag(currentState(key.uuid, env).toOption.get).toOption.get
      case _ => uObject
    }
  }

  def asType(uObject: UntaggedObject, env: Environment): Outcome[NewMapType, String] = {
    env.typeSystem.convertToNewMapType(uObject)
  }
}