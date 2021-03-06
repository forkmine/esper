/*
 ***************************************************************************************
 *  Copyright (C) 2006 EsperTech, Inc. All rights reserved.                            *
 *  http://www.espertech.com/esper                                                     *
 *  http://www.espertech.com                                                           *
 *  ---------------------------------------------------------------------------------- *
 *  The software in this package is published under the terms of the GPL license       *
 *  a copy of which has been included with this distribution in the license.txt file.  *
 ***************************************************************************************
 */
package com.espertech.esper.epl.core.resultset.rowpergroup;

import com.espertech.esper.client.EventBean;
import com.espertech.esper.codegen.base.CodegenBlock;
import com.espertech.esper.codegen.base.CodegenClassScope;
import com.espertech.esper.codegen.base.CodegenMethodNode;
import com.espertech.esper.collection.UniformPair;
import com.espertech.esper.core.context.util.AgentInstanceContext;
import com.espertech.esper.epl.agg.service.AggregationService;
import com.espertech.esper.epl.core.orderby.OrderByProcessor;
import com.espertech.esper.epl.core.resultset.codegen.ResultSetProcessorCodegenInstance;
import com.espertech.esper.epl.core.resultset.core.ResultSetProcessorUtil;
import com.espertech.esper.epl.core.select.SelectExprProcessor;
import com.espertech.esper.view.Viewable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static com.espertech.esper.codegen.model.expression.CodegenExpressionBuilder.*;
import static com.espertech.esper.epl.core.resultset.codegen.ResultSetProcessorCodegenNames.*;
import static com.espertech.esper.epl.core.resultset.core.ResultSetProcessorUtil.METHOD_TOPAIRNULLIFALLNULL;
import static com.espertech.esper.epl.core.resultset.grouped.ResultSetProcessorGroupedUtil.generateGroupKeySingleCodegen;
import static com.espertech.esper.epl.expression.codegen.ExprForgeCodegenNames.NAME_EPS;
import static com.espertech.esper.epl.expression.codegen.ExprForgeCodegenNames.REF_EPS;

public class ResultSetProcessorRowPerGroupUnbound extends ResultSetProcessorRowPerGroupImpl {

    private final ResultSetProcessorRowPerGroupUnboundHelper groupReps;

    ResultSetProcessorRowPerGroupUnbound(ResultSetProcessorRowPerGroupFactory prototype, SelectExprProcessor selectExprProcessor, OrderByProcessor orderByProcessor, AggregationService aggregationService, AgentInstanceContext agentInstanceContext) {
        super(prototype, selectExprProcessor, orderByProcessor, aggregationService, agentInstanceContext);
        groupReps = prototype.getResultSetProcessorHelperFactory().makeRSRowPerGroupUnboundGroupRep(agentInstanceContext, prototype.getGroupKeyTypes());
        aggregationService.setRemovedCallback(groupReps);
    }

    @Override
    public void applyViewResult(EventBean[] newData, EventBean[] oldData) {
        EventBean[] eventsPerStream = new EventBean[1];
        if (newData != null) {
            for (EventBean aNewData : newData) {
                eventsPerStream[0] = aNewData;
                Object mk = generateGroupKeySingle(eventsPerStream, true);
                groupReps.put(mk, aNewData);
                aggregationService.applyEnter(eventsPerStream, mk, agentInstanceContext);
            }
        }
        if (oldData != null) {
            for (EventBean anOldData : oldData) {
                eventsPerStream[0] = anOldData;
                Object mk = generateGroupKeySingle(eventsPerStream, false);
                aggregationService.applyLeave(eventsPerStream, mk, agentInstanceContext);
            }
        }
    }

    public static void applyViewResultCodegen(ResultSetProcessorRowPerGroupForge forge, CodegenClassScope classScope, CodegenMethodNode method, ResultSetProcessorCodegenInstance instance) {
        CodegenMethodNode generateGroupKeyViewSingle = generateGroupKeySingleCodegen(forge.getGroupKeyNodeExpressions(), classScope, instance);

        method.getBlock().declareVar(EventBean[].class, NAME_EPS, newArrayByLength(EventBean.class, constant(1)));

        {
            CodegenBlock ifNew = method.getBlock().ifCondition(notEqualsNull(REF_NEWDATA));
            {
                CodegenBlock newLoop = ifNew.forEach(EventBean.class, "aNewData", REF_NEWDATA);
                newLoop.assignArrayElement(NAME_EPS, constant(0), ref("aNewData"))
                        .declareVar(Object.class, "mk", localMethod(generateGroupKeyViewSingle, REF_EPS, constantTrue()))
                        .exprDotMethod(ref("groupReps"), "put", ref("mk"), ref("aNewData"))
                        .exprDotMethod(REF_AGGREGATIONSVC, "applyEnter", REF_EPS, ref("mk"), REF_AGENTINSTANCECONTEXT);
            }
        }

        {
            CodegenBlock ifOld = method.getBlock().ifCondition(notEqualsNull(REF_OLDDATA));
            {
                CodegenBlock oldLoop = ifOld.forEach(EventBean.class, "anOldData", REF_OLDDATA);
                oldLoop.assignArrayElement(NAME_EPS, constant(0), ref("anOldData"))
                        .declareVar(Object.class, "mk", localMethod(generateGroupKeyViewSingle, REF_EPS, constantFalse()))
                        .exprDotMethod(REF_AGGREGATIONSVC, "applyLeave", REF_EPS, ref("mk"), REF_AGENTINSTANCECONTEXT);
            }
        }
    }

    @Override
    public UniformPair<EventBean[]> processViewResult(EventBean[] newData, EventBean[] oldData, boolean isSynthesize) {
        // Generate group-by keys for all events, collect all keys in a set for later event generation
        Map<Object, EventBean> keysAndEvents = new HashMap<>();
        EventBean[] eventsPerStream = new EventBean[1];

        Object[] newDataMultiKey = generateGroupKeysKeepEvent(newData, keysAndEvents, true, eventsPerStream);
        Object[] oldDataMultiKey = generateGroupKeysKeepEvent(oldData, keysAndEvents, false, eventsPerStream);

        EventBean[] selectOldEvents = null;
        if (prototype.isSelectRStream()) {
            selectOldEvents = generateOutputEventsView(keysAndEvents, false, isSynthesize, eventsPerStream);
        }

        // update aggregates
        if (newData != null) {
            // apply new data to aggregates
            for (int i = 0; i < newData.length; i++) {
                eventsPerStream[0] = newData[i];
                groupReps.put(newDataMultiKey[i], eventsPerStream[0]);
                aggregationService.applyEnter(eventsPerStream, newDataMultiKey[i], agentInstanceContext);
            }
        }
        if (oldData != null) {
            // apply old data to aggregates
            for (int i = 0; i < oldData.length; i++) {
                eventsPerStream[0] = oldData[i];
                aggregationService.applyLeave(eventsPerStream, oldDataMultiKey[i], agentInstanceContext);
            }
        }

        // generate new events using select expressions
        EventBean[] selectNewEvents = generateOutputEventsView(keysAndEvents, true, isSynthesize, eventsPerStream);

        return ResultSetProcessorUtil.toPairNullIfAllNull(selectNewEvents, selectOldEvents);
    }

    static void processViewResultUnboundCodegen(ResultSetProcessorRowPerGroupForge forge, CodegenClassScope classScope, CodegenMethodNode method, ResultSetProcessorCodegenInstance instance) {
        CodegenMethodNode generateGroupKeysKeepEvent = generateGroupKeysKeepEventCodegen(forge, classScope, instance);
        CodegenMethodNode generateOutputEventsView = generateOutputEventsViewCodegen(forge, classScope, instance);

        method.getBlock().declareVar(Map.class, "keysAndEvents", newInstance(HashMap.class))
                .declareVar(EventBean[].class, NAME_EPS, newArrayByLength(EventBean.class, constant(1)))
                .declareVar(Object[].class, "newDataMultiKey", localMethod(generateGroupKeysKeepEvent, REF_NEWDATA, ref("keysAndEvents"), constantTrue(), REF_EPS))
                .declareVar(Object[].class, "oldDataMultiKey", localMethod(generateGroupKeysKeepEvent, REF_OLDDATA, ref("keysAndEvents"), constantFalse(), REF_EPS))
                .declareVar(EventBean[].class, "selectOldEvents", forge.isSelectRStream() ? localMethod(generateOutputEventsView, ref("keysAndEvents"), constantFalse(), REF_ISSYNTHESIZE, REF_EPS) : constantNull());

        {
            CodegenBlock ifNew = method.getBlock().ifCondition(notEqualsNull(REF_NEWDATA));
            {
                CodegenBlock newLoop = ifNew.forLoopIntSimple("i", arrayLength(REF_NEWDATA));
                newLoop.assignArrayElement(NAME_EPS, constant(0), arrayAtIndex(REF_NEWDATA, ref("i")))
                        .exprDotMethod(ref("groupReps"), "put", arrayAtIndex(ref("newDataMultiKey"), ref("i")), arrayAtIndex(REF_EPS, constant(0)))
                        .exprDotMethod(REF_AGGREGATIONSVC, "applyEnter", REF_EPS, arrayAtIndex(ref("newDataMultiKey"), ref("i")), REF_AGENTINSTANCECONTEXT);
            }
        }

        {
            CodegenBlock ifOld = method.getBlock().ifCondition(notEqualsNull(REF_OLDDATA));
            {
                CodegenBlock newLoop = ifOld.forLoopIntSimple("i", arrayLength(REF_OLDDATA));
                newLoop.assignArrayElement(NAME_EPS, constant(0), arrayAtIndex(REF_OLDDATA, ref("i")))
                        .exprDotMethod(REF_AGGREGATIONSVC, "applyLeave", REF_EPS, arrayAtIndex(ref("oldDataMultiKey"), ref("i")), REF_AGENTINSTANCECONTEXT);
            }
        }

        method.getBlock().declareVar(EventBean[].class, "selectNewEvents", localMethod(generateOutputEventsView, ref("keysAndEvents"), constantTrue(), REF_ISSYNTHESIZE, REF_EPS))
                .methodReturn(staticMethod(ResultSetProcessorUtil.class, METHOD_TOPAIRNULLIFALLNULL, ref("selectNewEvents"), ref("selectOldEvents")));
    }

    @Override
    public Iterator<EventBean> getIterator(Viewable parent) {
        if (orderByProcessor == null) {
            Iterator<EventBean> it = groupReps.valueIterator();
            return new ResultSetProcessorRowPerGroupIterator(it, this, aggregationService, agentInstanceContext);
        }
        return getIteratorSorted(groupReps.valueIterator());
    }

    public static void getIteratorViewUnboundedCodegen(ResultSetProcessorRowPerGroupForge forge, CodegenClassScope classScope, CodegenMethodNode method, ResultSetProcessorCodegenInstance instance) {
        if (!forge.isSorting()) {
            method.getBlock().declareVar(Iterator.class, "it", exprDotMethod(ref("groupReps"), "valueIterator"))
                    .methodReturn(newInstance(ResultSetProcessorRowPerGroupIterator.class, ref("it"), ref("this"), REF_AGGREGATIONSVC, REF_AGENTINSTANCECONTEXT));
        } else {
            CodegenMethodNode getIteratorSorted = getIteratorSortedCodegen(forge, classScope, instance);
            method.getBlock().methodReturn(localMethod(getIteratorSorted, exprDotMethod(ref("groupReps"), "valueIterator")));
        }
    }

    @Override
    public void stop() {
        super.stop();
        groupReps.destroy();
    }

    public static void stopMethodCodegen(ResultSetProcessorRowPerGroupForge forge, CodegenClassScope classScope, CodegenMethodNode method, ResultSetProcessorCodegenInstance instance) {
        ResultSetProcessorRowPerGroupImpl.stopMethodCodegen(method, instance);
        exprDotMethod(ref("groupReps"), "destroy");
    }
}
