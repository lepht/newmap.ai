package ai.newmap.interpreter

import ai.newmap.interpreter.TypeChecker.{patternToExpression, typeCheck, typeCheckGenericMap}
import ai.newmap.model._
import ai.newmap.util.{Failure, Outcome, Success}

object StatementInterpreter {
  case class ReturnValue(
    command: EnvironmentCommand,
    tcParameters: Map[String, NewMapType]
  )

  /*
   * @param sParse The statement parses
   * @param env This is a map of identifiers which at this point are supposed to be subsituted.
   * @return Do not execute the statement, only reason about the values that it will return to add to the parameters.
   */
  def apply(
    sParse: EnvStatementParse,
    env: Environment,
    tcParameters: Map[String, NewMapType]
  ): Outcome[ReturnValue, String] = {
    sParse match {
      case FullStatementParse(prefix, id, typeExpression, objExpression) => {
        for {
          tcType <- TypeChecker.typeCheck(typeExpression, TypeT, env, FullFunction, tcParameters)
          nTypeObj <- Evaluator(tcType.nExpression, env)
          nType <- Evaluator.asType(nTypeObj, env)


          // If prefix is DefStatement then make sure nType is a potentially recursive function!
          // Also update the environment with the name because it's potentially recursive
          // TODO - shouldn't this check happen in the type checker?
          newParams <- nType match {
            case _ if (prefix != DefStatement) => Success(tcParameters)
            case MapT(_, MapConfig(_, featureSet, _, _, _)) if (featureSet.getLevel >= WellFoundedFunction.getLevel) => Success(tcParameters + (id.s -> nType))
            case _ => Failure("A def statment should define a function that is Full or Well Founded. For other values or functions, use a val or ver statement instead")
          }

          tc <- TypeChecker.typeCheck(objExpression, nType, env, FullFunction, newParams)
          
          nObject = NewMapObject(tc.nExpression, nType)
        } yield {
          ReturnValue(
            FullEnvironmentCommand(id.s, nObject, prefix == DefStatement),
            newParams
          )
        }
      }
      case NewVersionedStatementParse(id, typeExpression) => {
        for {
          tcType <- typeCheck(typeExpression, TypeT, env, FullFunction, tcParameters)
          nTypeObj <- Evaluator(tcType.nExpression, env)
          nType <- Evaluator.asType(nTypeObj, env)

          // Problem is WITH_TYPE!!

          // TODO: Maybe a special error message if this is not a command type
          // - In fact, we have yet to build an actual command type checker
          initValue <- CommandMaps.getDefaultValueOfCommandType(nType, env)
        } yield {
          ReturnValue(
            NewVersionedStatementCommand(id.s, nType),
            tcParameters + (id.s -> nType)
          )
        }
      }
      case NewTypeStatementParse(id, typeExpression) => {
        for {
          tcType <- typeCheck(typeExpression, TypeT, env, FullFunction, tcParameters)
          nTypeObj <- Evaluator(tcType.nExpression, env)
          nType <- Evaluator.asType(nTypeObj, env)
        } yield {
          ReturnValue(
            NewTypeCommand(id.s, nType),
            tcParameters + (id.s -> TypeT)
          )
        }
      }
      case NewParamTypeStatementParse(id, params) => {
        val values = params match {
          case LiteralListParse(vs, MapType) => vs
          case _ => Vector(params) 
        }

        val typeTransform = UMapPattern(
          env.typeSystem.typeToUntaggedObject(IdentifierT), patternToExpression(env.typeSystem.typeToUntaggedObject(TypeT))
        )

        for {
          mapValues <- typeCheckGenericMap(values, typeTransform, BasicMap, env, FullFunction, Map.empty)
          paramList <- convertMapValuesToParamList(mapValues, env)
        } yield {
          ReturnValue(
            NewParamTypeCommand(id.s, paramList, CaseT(UMap(Vector.empty), IdentifierT)),
            tcParameters
          )
        }
      }
      case NewTypeClassStatementParse(id, typeTransformParse) => {
        val typeOfTypeTransform = MapT(
          UMap(Vector(env.typeSystem.typeToUntaggedObject(TypeT) -> env.typeSystem.typeToUntaggedObject(TypeT))),
          MapConfig(MapPattern, PatternMap)
        )

        for {
          typeTransformResult <- TypeChecker.typeCheck(typeTransformParse, typeOfTypeTransform, env, FullFunction, tcParameters)

          typeTransform <- typeTransformResult.nExpression match {
            case result@UMapPattern(_, _) => Success(result)
            case _ => Failure(s"Invalid type transform: ${typeTransformResult.nExpression}")
          }
        } yield {
          ReturnValue(
            NewTypeClassCommand(id.s, typeTransform),
            tcParameters
          )
        }
      }
      case IterateIntoStatementParse(iterableExp, destination) => {
        for {
          // TODO - we need a type inference here!!
          tc <- TypeChecker.typeCheckUnknownType(iterableExp, env, Map.empty)
          evaluatedObject <- Evaluator(tc.nExpression, env)
          constantObject = Evaluator.stripVersioningU(evaluatedObject, env)
          iterableObjectCandidate <- TypeChecker.tagAndNormalizeObject(constantObject, tc.refinedTypeClass, env)

          // NOTE: This is a very inefficient way (we are calling the whole function to see if we can call the function)
          // - In the future, this should be taken into account by the type checker
          // - the type checker, instead of using unknown type, will get a hint that this is an iterable object, and
          //   will use that hint to build it!
          iterableObject <- IterationUtils.iterateObject(iterableObjectCandidate, env) match {
            case Success(_) => Success(iterableObjectCandidate)
            case _ => {
              for {
                tcType <- TypeChecker.typeCheck(iterableExp, TypeT, env, FullFunction, tcParameters)
                uType <- Evaluator(tcType.nExpression, env)
                iterableTypeCandidate = NewMapObject(uType, HistoricalTypeT(env.typeSystem.currentState))
              } yield iterableTypeCandidate
            }
          }

          // destination can either be a CHANNEL or it can be a VERSIONED OBJECT
          // which is it?
          // TODO - eventually merge the 2 concepts!

          channelTypeOpt = env.channelIdToType.get(destination.s)

          command <- channelTypeOpt match {
            case Some(channelType) => {
              for {
                tObject <- SubtypeUtils.attemptConvertObjectToType(iterableObject, channelType, env)
              } yield {
                IterateIntoChannel(tObject.uObject, UIdentifier(destination.s))
              }
            }
            case None => {
              // IT's an object, not a channel
              for {
                vDestination <- env.lookupVersionedObject(destination.s)
              } yield {
                IterateIntoCommand(iterableObject, destination.s)
              }
            }
          }
        } yield {
          ReturnValue(command, tcParameters)
        }
      }
      case ForkedVersionedStatementParse(id, forkId) => {
        for {
          vObject <- env.lookupVersionedObject(forkId.s)
        } yield {
          ReturnValue(
            ForkEnvironmentCommand(id.s, vObject.key),
            tcParameters + (id.s -> vObject.nType)
          )
        }
      }
      case ApplyCommandStatementParse(id, command) => {
        (env.lookupVersionedObject(id.s), tcParameters.get(id.s)) match {
          case (_, Some(nType)) => {
            for {
              inputT <- CommandMaps.getCommandInputOfCommandType(nType, env)
              commandExp <- typeCheck(command, inputT, env, FullFunction, tcParameters)
            } yield {
              ReturnValue(
                ApplyIndividualCommand(id.s, commandExp.nExpression),
                tcParameters
              )
            }
          }
          case (Success(versionedObjectLink), _) => {
            for {
              inputT <- CommandMaps.getCommandInputOfCommandType(versionedObjectLink.nType, env)
              commandExp <- typeCheck(command, inputT, env, FullFunction, tcParameters)
            } yield {
              ReturnValue(
                ApplyIndividualCommand(id.s, commandExp.nExpression),
                tcParameters
              )
            }
          }
          case (Failure(objectLookupFailureMessage), _) => {
            val typeSystem = env.typeSystem
            val currentState = typeSystem.currentState

            for {
              latestNamespace <- Outcome(typeSystem.historicalMapping.get(currentState), s"Type System missing latest namespace $currentState")
              typeId <- Outcome(latestNamespace.get(id.s), s"Couldn't update variable ${id.s}. Not found in object or type namespace. Object space failure: $objectLookupFailureMessage")

              currentUnderlyingType <- Outcome(typeSystem.typeToUnderlyingType.get(typeId), s"Couldn't find underlying type for ${id.s}")

              currentParameterPattern = currentUnderlyingType._1
              currentUnderlyingExp = currentUnderlyingType._2

              underlyingT <- typeSystem.convertToNewMapType(currentUnderlyingExp)

              inputT <- CommandMaps.getTypeExpansionCommandInput(underlyingT, typeSystem)

              newParameterMap <- RetrieveType.getParameterValues(id.s, env)

              commandExp <- typeCheck(command, inputT, env, FullFunction, newParameterMap)
            } yield {
              ReturnValue(
                ApplyIndividualCommand(id.s, commandExp.nExpression),
                tcParameters
              )
            }
          }
        }
      }
      case ApplyCommandsStatementParse(id, commands) => {
        throw new Exception("Apply multiple commands not yet implemented")
      }
      case AddChannelParse(channelId, channelTypeParse) => {
        val channel = UIdentifier(channelId.s)
        for {
          tcType <- TypeChecker.typeCheck(channelTypeParse, TypeT, env, FullFunction, tcParameters)
          nTypeObj <- Evaluator(tcType.nExpression, env)
          nType <- Evaluator.asType(nTypeObj, env)
        } yield {
          ReturnValue(
            AddChannel(channel, nType),
            tcParameters
          )
        }
      }
      case ConnectChannelParse(channelId, obj) => {
        val channel = UIdentifier(channelId.s)
        env.lookupVersionedObject(obj.s) match {
          case Success(versionedObjectLink) => {
            for {
              inputT <- CommandMaps.getCommandInputOfCommandType(versionedObjectLink.nType, env)

              channelType = env.channelIdToType.get(channelId.s).getOrElse(UndefinedT)

              // So channelType is what's coming from the channel, and inputT is what's required
              // So we want to make sure that channelType is convertible to inputT

              //_ = println(s"channelType: ${channelType.displayString(env)} -- ${inputT.displayString(env)}")
              
              // Looks like channelType is String
              // And inputT is character
              // Should be doable to connect!

              channelCanBeConnectedToObject <- IterationUtils.isIteratableToType(channelType, inputT, env)
              _ <- Outcome.failWhen(!channelCanBeConnectedToObject, s"can't connect channel ${channelId.s} of type ${channelType.displayString(env)} to object ${obj.s} of type ${inputT.displayString(env)}")
            } yield {
              ReturnValue(
                ConnectChannel(channel, obj.s),
                tcParameters
              )
            }
          }
          case Failure(reason) => {
            throw new Exception("Cannot yet connect channel to a type")
          }
        }
      }
      case DisconnectChannelParse(channelId, obj) => {
        val channel = UIdentifier(channelId.s)
        env.lookupVersionedObject(obj.s) match {
          case Success(versionedObjectLink) => {
            // No need for type checking when we are disconnecting
            Success(
              ReturnValue(
                DisconnectChannel(channel, obj.s),
                tcParameters
              )
            )
          }
          case Failure(reason) => {
            throw new Exception("Cannot yet disconnect channel from a type")
          }
        }
      }
      case WriteToChannelParse(channelId, command) => {
        val channel = UIdentifier(channelId.s)
        val nType = env.channelIdToType.get(channelId.s).getOrElse(UndefinedT)
        for {
          tc <- TypeChecker.typeCheck(command, nType, env, FullFunction, tcParameters)
        } yield {
          ReturnValue(
            OutputToChannel(tc.nExpression, channel),
            tcParameters
          )
        }
      }
      case InferredTypeStatementParse(_, id, objExpression) => {
        for {
          // This infers the type using the output "refinedTypeClass"
          tc <- TypeChecker.typeCheckUnknownType(objExpression, env, Map.empty)
        } yield {
          val nObject = NewMapObject(tc.nExpression, tc.refinedTypeClass)
          ReturnValue(
            FullEnvironmentCommand(id.s, nObject, false),
            tcParameters
          )
        }
      }
      case ExpressionOnlyStatementParse(exp) => {
        for {
          // This infers the type using the output "refinedTypeClass"
          tc <- TypeChecker.typeCheckUnknownType(exp, env, Map.empty)
        } yield {
          val nObject = NewMapObject(tc.nExpression, tc.refinedTypeClass)

          ReturnValue(
            ExpOnlyEnvironmentCommand(nObject),
            tcParameters
          )
        }
      }
      case EmptyStatement => Success(ReturnValue(EmptyEnvironmentCommand, tcParameters))
    }
  }

  def convertMapValuesToParamList(
    mapValues: Vector[(UntaggedObject, UntaggedObject)],
    env: Environment
  ): Outcome[Vector[(String, NewMapType)], String] = {
    mapValues match {
      case (pattern, expression) +: restOfMapValues => {
        for {
          k <- pattern match {
            case UIdentifier(s) => Success(s)
            case _ => Failure(s"Pattern $pattern should have been an identifier")
          }

          uObject <- Evaluator(expression, env)
          v <- Evaluator.asType(uObject, env)

          restOfResult <- convertMapValuesToParamList(restOfMapValues, env)
        } yield (k -> v) +: restOfResult
      }
      case _ => Success(Vector.empty)
    }
  }
}