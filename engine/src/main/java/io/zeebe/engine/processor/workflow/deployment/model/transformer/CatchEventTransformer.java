/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.engine.processor.workflow.deployment.model.transformer;

import io.zeebe.el.Expression;
import io.zeebe.el.ExpressionLanguage;
import io.zeebe.engine.processor.Failure;
import io.zeebe.engine.processor.workflow.deployment.model.BpmnStep;
import io.zeebe.engine.processor.workflow.deployment.model.element.ExecutableCatchEventElement;
import io.zeebe.engine.processor.workflow.deployment.model.element.ExecutableMessage;
import io.zeebe.engine.processor.workflow.deployment.model.element.ExecutableWorkflow;
import io.zeebe.engine.processor.workflow.deployment.model.transformation.ModelElementTransformer;
import io.zeebe.engine.processor.workflow.deployment.model.transformation.TransformContext;
import io.zeebe.model.bpmn.instance.CatchEvent;
import io.zeebe.model.bpmn.instance.ErrorEventDefinition;
import io.zeebe.model.bpmn.instance.EventDefinition;
import io.zeebe.model.bpmn.instance.Message;
import io.zeebe.model.bpmn.instance.MessageEventDefinition;
import io.zeebe.model.bpmn.instance.TimerEventDefinition;
import io.zeebe.model.bpmn.util.time.RepeatingInterval;
import io.zeebe.model.bpmn.util.time.TimeDateTimer;
import io.zeebe.protocol.record.intent.WorkflowInstanceIntent;
import io.zeebe.util.Either;
import java.time.format.DateTimeParseException;

public final class CatchEventTransformer implements ModelElementTransformer<CatchEvent> {

  @Override
  public Class<CatchEvent> getType() {
    return CatchEvent.class;
  }

  @Override
  public void transform(final CatchEvent element, final TransformContext context) {
    final ExecutableWorkflow workflow = context.getCurrentWorkflow();
    final ExecutableCatchEventElement executableElement =
        workflow.getElementById(element.getId(), ExecutableCatchEventElement.class);

    executableElement.bindLifecycleState(
        WorkflowInstanceIntent.ELEMENT_COMPLETED, BpmnStep.FLOWOUT_ELEMENT_COMPLETED);

    if (!element.getEventDefinitions().isEmpty()) {
      transformEventDefinition(element, context, executableElement);
    }
  }

  private void transformEventDefinition(
      final CatchEvent element,
      final TransformContext context,
      final ExecutableCatchEventElement executableElement) {
    final EventDefinition eventDefinition = element.getEventDefinitions().iterator().next();
    if (eventDefinition instanceof MessageEventDefinition) {
      transformMessageEventDefinition(
          context, executableElement, (MessageEventDefinition) eventDefinition);

    } else if (eventDefinition instanceof TimerEventDefinition) {
      final var expressionLanguage = context.getExpressionLanguage();
      final var timerDefinition = (TimerEventDefinition) eventDefinition;
      transformTimerEventDefinition(expressionLanguage, executableElement, timerDefinition);

    } else if (eventDefinition instanceof ErrorEventDefinition) {
      transformErrorEventDefinition(
          context, executableElement, (ErrorEventDefinition) eventDefinition);
    }
  }

  private void transformMessageEventDefinition(
      final TransformContext context,
      final ExecutableCatchEventElement executableElement,
      final MessageEventDefinition messageEventDefinition) {

    final Message message = messageEventDefinition.getMessage();
    final ExecutableMessage executableMessage = context.getMessage(message.getId());
    executableElement.setMessage(executableMessage);
  }

  private void transformTimerEventDefinition(
      final ExpressionLanguage expressionLanguage,
      final ExecutableCatchEventElement executableElement,
      final TimerEventDefinition timerEventDefinition) {

    final Expression expression;
    if (timerEventDefinition.getTimeDuration() != null) {
      final String duration = timerEventDefinition.getTimeDuration().getTextContent();
      expression = expressionLanguage.parseExpression(duration);
      executableElement.setTimerFactory(
          (expressionProcessor, scopeKey) ->
              expressionProcessor
                  .evaluateIntervalExpression(expression, scopeKey)
                  .map(interval -> new RepeatingInterval(1, interval)));

    } else if (timerEventDefinition.getTimeCycle() != null) {
      final String cycle = timerEventDefinition.getTimeCycle().getTextContent();
      expression = expressionLanguage.parseExpression(cycle);
      executableElement.setTimerFactory(
          (expressionProcessor, scopeKey) -> {
            try {
              return expressionProcessor
                  .evaluateStringExpression(expression, scopeKey)
                  .map(RepeatingInterval::parse);
            } catch (DateTimeParseException e) {
              // todo(#4323): replace this caught exception with Either
              return Either.left(new Failure(e.getMessage()));
            }
          });

    } else if (timerEventDefinition.getTimeDate() != null) {
      final String timeDate = timerEventDefinition.getTimeDate().getTextContent();
      expression = expressionLanguage.parseExpression(timeDate);
      executableElement.setTimerFactory(
          (expressionProcessor, scopeKey) ->
              expressionProcessor
                  .evaluateDateTimeExpression(expression, scopeKey)
                  .map(TimeDateTimer::new));
    }
  }

  private void transformErrorEventDefinition(
      final TransformContext context,
      final ExecutableCatchEventElement executableElement,
      final ErrorEventDefinition errorEventDefinition) {

    final var error = errorEventDefinition.getError();
    final var executableError = context.getError(error.getId());
    executableElement.setError(executableError);
  }
}
