/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.engine.processor.workflow;

import static io.zeebe.util.buffer.BufferUtil.cloneBuffer;

import io.zeebe.el.Expression;
import io.zeebe.engine.processor.Failure;
import io.zeebe.engine.processor.TypedStreamWriter;
import io.zeebe.engine.processor.workflow.ExpressionProcessor.EvaluationException;
import io.zeebe.engine.processor.workflow.deployment.model.element.ExecutableCatchEvent;
import io.zeebe.engine.processor.workflow.deployment.model.element.ExecutableCatchEventSupplier;
import io.zeebe.engine.processor.workflow.deployment.model.element.ExecutableMessage;
import io.zeebe.engine.processor.workflow.message.MessageCorrelationKeyContext;
import io.zeebe.engine.processor.workflow.message.MessageCorrelationKeyContext.VariablesDocumentSupplier;
import io.zeebe.engine.processor.workflow.message.MessageCorrelationKeyException;
import io.zeebe.engine.processor.workflow.message.MessageNameException;
import io.zeebe.engine.processor.workflow.message.command.SubscriptionCommandSender;
import io.zeebe.engine.state.ZeebeState;
import io.zeebe.engine.state.instance.TimerInstance;
import io.zeebe.engine.state.message.WorkflowInstanceSubscription;
import io.zeebe.model.bpmn.util.time.Timer;
import io.zeebe.protocol.impl.SubscriptionUtil;
import io.zeebe.protocol.impl.record.value.timer.TimerRecord;
import io.zeebe.protocol.record.intent.TimerIntent;
import io.zeebe.protocol.record.value.BpmnElementType;
import io.zeebe.util.Either;
import io.zeebe.util.buffer.BufferUtil;
import io.zeebe.util.sched.clock.ActorClock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.agrona.DirectBuffer;

public final class CatchEventBehavior {

  private final ZeebeState state;
  private final ExpressionProcessor expressionProcessor;
  private final SubscriptionCommandSender subscriptionCommandSender;
  private final int partitionsCount;

  private final WorkflowInstanceSubscription subscription = new WorkflowInstanceSubscription();
  private final TimerRecord timerRecord = new TimerRecord();
  private final Map<DirectBuffer, DirectBuffer> extractedCorrelationKeys = new HashMap<>();
  private final Map<DirectBuffer, Timer> evaluatedTimers = new HashMap<>();

  public CatchEventBehavior(
      final ZeebeState state,
      final ExpressionProcessor exporessionProcessor,
      final SubscriptionCommandSender subscriptionCommandSender,
      final int partitionsCount) {
    this.state = state;
    expressionProcessor = exporessionProcessor;
    this.subscriptionCommandSender = subscriptionCommandSender;
    this.partitionsCount = partitionsCount;
  }

  public void unsubscribeFromEvents(
      final long elementInstanceKey, final BpmnStepContext<?> context) {
    unsubscribeFromTimerEvents(elementInstanceKey, context.getOutput().getStreamWriter());
    unsubscribeFromMessageEvents(elementInstanceKey, context);
    context.getStateDb().getEventScopeInstanceState().deleteInstance(elementInstanceKey);
  }

  public void subscribeToEvents(
      final BpmnStepContext<?> context, final ExecutableCatchEventSupplier supplier)
      throws MessageCorrelationKeyException {
    final List<ExecutableCatchEvent> events = supplier.getEvents();
    final VariablesDocumentSupplier variablesSupplier =
        context.getElementInstanceState().getVariablesState()::getVariablesAsDocument;
    final MessageCorrelationKeyContext elementContext =
        new MessageCorrelationKeyContext(variablesSupplier, context.getKey());
    final MessageCorrelationKeyContext scopeContext =
        new MessageCorrelationKeyContext(variablesSupplier, context.getValue().getFlowScopeKey());

    // collect all message names from their respective variables, as this might fail and
    // we might need to raise an incident
    final Map<DirectBuffer, DirectBuffer> extractedMessageNames =
        extractMessageNames(events, context);
    // collect all message correlation keys from their respective variables, as this might fail and
    // we might need to raise an incident. This works the same for timers
    final Map<DirectBuffer, DirectBuffer> extractedCorrelationKeys =
        extractMessageCorrelationKeys(events, elementContext, scopeContext);
    final Map<DirectBuffer, Timer> evaluatedTimers = evaluateTimers(events, context.getKey());

    // if all subscriptions are valid then open the subscriptions
    for (final ExecutableCatchEvent event : events) {
      if (event.isTimer()) {
        subscribeToTimerEvent(
            context.getKey(),
            context.getValue().getWorkflowInstanceKey(),
            context.getValue().getWorkflowKey(),
            event.getId(),
            evaluatedTimers.get(event.getId()),
            context.getOutput().getStreamWriter());
      } else if (event.isMessage()) {
        subscribeToMessageEvent(
            context,
            event,
            extractedCorrelationKeys.get(event.getId()),
            extractedMessageNames.get((event.getId())));
      }
    }

    context
        .getStateDb()
        .getEventScopeInstanceState()
        .createIfNotExists(context.getKey(), supplier.getInterruptingElementIds());
  }

  public void subscribeToTimerEvent(
      final long elementInstanceKey,
      final long workflowInstanceKey,
      final long workflowKey,
      final DirectBuffer handlerNodeId,
      final Timer timer,
      final TypedStreamWriter writer) {
    timerRecord.reset();
    timerRecord
        .setRepetitions(timer.getRepetitions())
        .setDueDate(timer.getDueDate(ActorClock.currentTimeMillis()))
        .setElementInstanceKey(elementInstanceKey)
        .setWorkflowInstanceKey(workflowInstanceKey)
        .setTargetElementId(handlerNodeId)
        .setWorkflowKey(workflowKey);
    writer.appendNewCommand(TimerIntent.CREATE, timerRecord);
  }

  private void unsubscribeFromTimerEvents(
      final long elementInstanceKey, final TypedStreamWriter writer) {
    state
        .getWorkflowState()
        .getTimerState()
        .forEachTimerForElementInstance(
            elementInstanceKey, t -> unsubscribeFromTimerEvent(t, writer));
  }

  public void unsubscribeFromTimerEvent(final TimerInstance timer, final TypedStreamWriter writer) {
    timerRecord.reset();
    timerRecord
        .setElementInstanceKey(timer.getElementInstanceKey())
        .setWorkflowInstanceKey(timer.getWorkflowInstanceKey())
        .setDueDate(timer.getDueDate())
        .setRepetitions(timer.getRepetitions())
        .setTargetElementId(timer.getHandlerNodeId())
        .setWorkflowKey(timer.getWorkflowKey());

    writer.appendFollowUpCommand(timer.getKey(), TimerIntent.CANCEL, timerRecord);
  }

  private void subscribeToMessageEvent(
      final BpmnStepContext<?> context,
      final ExecutableCatchEvent handler,
      final DirectBuffer extractedKey,
      final DirectBuffer extractedMessageName) {
    final long workflowInstanceKey = context.getValue().getWorkflowInstanceKey();
    final DirectBuffer bpmnProcessId = cloneBuffer(context.getValue().getBpmnProcessIdBuffer());
    final long elementInstanceKey = context.getKey();

    final DirectBuffer correlationKey = extractedKey;
    final DirectBuffer messageName = extractedMessageName;
    final boolean closeOnCorrelate = handler.shouldCloseMessageSubscriptionOnCorrelate();
    final int subscriptionPartitionId =
        SubscriptionUtil.getSubscriptionPartitionId(correlationKey, partitionsCount);

    subscription.setSubscriptionPartitionId(subscriptionPartitionId);
    subscription.setMessageName(messageName);
    subscription.setElementInstanceKey(elementInstanceKey);
    subscription.setCommandSentTime(ActorClock.currentTimeMillis());
    subscription.setWorkflowInstanceKey(workflowInstanceKey);
    subscription.setBpmnProcessId(bpmnProcessId);
    subscription.setCorrelationKey(correlationKey);
    subscription.setTargetElementId(handler.getId());
    subscription.setCloseOnCorrelate(closeOnCorrelate);
    state.getWorkflowInstanceSubscriptionState().put(subscription);

    context
        .getSideEffect()
        .add(
            () ->
                sendOpenMessageSubscription(
                    subscriptionPartitionId,
                    workflowInstanceKey,
                    elementInstanceKey,
                    bpmnProcessId,
                    messageName,
                    correlationKey,
                    closeOnCorrelate));
  }

  private void unsubscribeFromMessageEvents(
      final long elementInstanceKey, final BpmnStepContext<?> context) {
    state
        .getWorkflowInstanceSubscriptionState()
        .visitElementSubscriptions(
            elementInstanceKey, sub -> unsubscribeFromMessageEvent(context, sub));
  }

  private boolean unsubscribeFromMessageEvent(
      final BpmnStepContext<?> context, final WorkflowInstanceSubscription subscription) {
    final DirectBuffer messageName = cloneBuffer(subscription.getMessageName());
    final int subscriptionPartitionId = subscription.getSubscriptionPartitionId();
    final long workflowInstanceKey = subscription.getWorkflowInstanceKey();
    final long elementInstanceKey = subscription.getElementInstanceKey();

    subscription.setClosing();
    state
        .getWorkflowInstanceSubscriptionState()
        .updateToClosingState(subscription, ActorClock.currentTimeMillis());

    context
        .getSideEffect()
        .add(
            () ->
                sendCloseMessageSubscriptionCommand(
                    subscriptionPartitionId, workflowInstanceKey, elementInstanceKey, messageName));

    return true;
  }

  private String extractCorrelationKey(
      final ExecutableMessage message, final MessageCorrelationKeyContext context) {

    final Expression correlationKeyExpression = message.getCorrelationKeyExpression();

    return expressionProcessor.evaluateMessageCorrelationKeyExpression(
        correlationKeyExpression, context);
  }

  private Optional<DirectBuffer> extractMessageName(
      final ExecutableMessage message, final BpmnStepContext context) {

    final Expression messageNameExpression = message.getMessageNameExpression();
    return expressionProcessor.evaluateStringExpression(messageNameExpression, context);
  }

  private boolean sendCloseMessageSubscriptionCommand(
      final int subscriptionPartitionId,
      final long workflowInstanceKey,
      final long elementInstanceKey,
      final DirectBuffer messageName) {
    return subscriptionCommandSender.closeMessageSubscription(
        subscriptionPartitionId, workflowInstanceKey, elementInstanceKey, messageName);
  }

  private boolean sendOpenMessageSubscription(
      final int subscriptionPartitionId,
      final long workflowInstanceKey,
      final long elementInstanceKey,
      final DirectBuffer bpmnProcessId,
      final DirectBuffer messageName,
      final DirectBuffer correlationKey,
      final boolean closeOnCorrelate) {
    return subscriptionCommandSender.openMessageSubscription(
        subscriptionPartitionId,
        workflowInstanceKey,
        elementInstanceKey,
        bpmnProcessId,
        messageName,
        correlationKey,
        closeOnCorrelate);
  }

  private Map<DirectBuffer, DirectBuffer> extractMessageCorrelationKeys(
      final List<ExecutableCatchEvent> events,
      final MessageCorrelationKeyContext elementContext,
      final MessageCorrelationKeyContext scopeContext) {
    extractedCorrelationKeys.clear();

    // TODO (saig0): extract logic to resolve the variable scope key
    //  see BpmnIncidentBehavior
    for (final ExecutableCatchEvent event : events) {
      if (event.isMessage()) {
        final MessageCorrelationKeyContext context =
            event.getElementType() == BpmnElementType.BOUNDARY_EVENT
                ? scopeContext
                : elementContext;
        final String correlationKey = extractCorrelationKey(event.getMessage(), context);

        extractedCorrelationKeys.put(event.getId(), BufferUtil.wrapString(correlationKey));
      }
    }

    return extractedCorrelationKeys;
  }

  private Map<DirectBuffer, Timer> evaluateTimers(
      final List<ExecutableCatchEvent> events, final long key) {
    evaluatedTimers.clear();

    for (final ExecutableCatchEvent event : events) {
      if (event.isTimer()) {
        final Either<Failure, Timer> timerOrError =
            event.getTimerFactory().apply(expressionProcessor, key);
        if (timerOrError.isLeft()) {
          // todo(#4323): deal with this exceptional case without throwing an exception
          throw new EvaluationException(timerOrError.getLeft().getMessage());
        }
        evaluatedTimers.put(event.getId(), timerOrError.get());
      }
    }

    return evaluatedTimers;
  }

  private Map<DirectBuffer, DirectBuffer> extractMessageNames(
      final List<ExecutableCatchEvent> events, final BpmnStepContext context) {
    final Map<DirectBuffer, DirectBuffer> extractedMessageNames = new HashMap<>();

    final List<DirectBuffer> eventIdsWithNameEvaluationFailures = new ArrayList<>();

    for (final ExecutableCatchEvent event : events) {
      if (event.isMessage()) {
        final Optional<DirectBuffer> messageName = extractMessageName(event.getMessage(), context);

        if (messageName.isPresent()) {
          extractedMessageNames.put(event.getId(), cloneBuffer(messageName.get()));
        } else {
          eventIdsWithNameEvaluationFailures.add(event.getId());
        }
      }
    }

    if (!eventIdsWithNameEvaluationFailures.isEmpty()) {
      throw new MessageNameException(context, eventIdsWithNameEvaluationFailures);
    }

    return extractedMessageNames;
  }
}
