/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.apache.kylin.query.routing;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.cube.CubeInstance;
import org.apache.kylin.metadata.model.ColumnDesc;
import org.apache.kylin.metadata.model.DataModelDesc;
import org.apache.kylin.metadata.model.JoinDesc;
import org.apache.kylin.metadata.model.JoinTableDesc;
import org.apache.kylin.metadata.model.JoinsTree;
import org.apache.kylin.metadata.model.TableRef;
import org.apache.kylin.metadata.model.TblColRef;
import org.apache.kylin.metadata.project.ProjectManager;
import org.apache.kylin.metadata.realization.IRealization;
import org.apache.kylin.query.relnode.OLAPContext;
import org.apache.kylin.query.relnode.OLAPTableScan;
import org.apache.kylin.query.routing.rules.RemoveBlackoutRealizationsRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

public class RealizationChooser {

    private static final Logger logger = LoggerFactory.getLogger(RealizationChooser.class);

    // select models for given contexts, return realization candidates for each context
    public static void selectRealization(List<OLAPContext> contexts) {
        // try different model for different context
        for (OLAPContext ctx : contexts) {
            attemptSelectRealization(ctx);
            Preconditions.checkNotNull(ctx.realization);
        }
    }

    private static void attemptSelectRealization(OLAPContext context) {
        Map<DataModelDesc, Set<IRealization>> modelMap = makeOrderedModelMap(context);

        if (modelMap.size() == 0) {
            throw new NoRealizationFoundException("No model found for" + toErrorMsg(context));
        }

        for (Map.Entry<DataModelDesc, Set<IRealization>> entry : modelMap.entrySet()) {
            final DataModelDesc model = entry.getKey();
            final Map<String, String> aliasMap = matches(model, context);
            if (aliasMap != null) {
                fixModel(context, model, aliasMap);

                IRealization realization = QueryRouter.selectRealization(context, entry.getValue());
                if (realization == null) {
                    logger.info("Give up on model {} because no suitable realization is found", model);
                    unfixModel(context);
                    continue;
                }

                context.realization = realization;
                return;
            }
        }

        throw new NoRealizationFoundException("No realization found for " + toErrorMsg(context));

    }

    private static String toErrorMsg(OLAPContext ctx) {
        StringBuilder buf = new StringBuilder();
        buf.append(ctx.firstTableScan);
        for (JoinDesc join : ctx.joins)
            buf.append(", ").append(join);
        return buf.toString();
    }

    private static Map<String, String> matches(DataModelDesc model, OLAPContext ctx) {
        Map<String, String> result = Maps.newHashMap();

        TableRef firstTable = ctx.firstTableScan.getTableRef();

        Map<String, String> matchUp = null;

        if (ctx.joins.isEmpty() && model.isLookupTable(firstTable.getTableIdentity())) {
            // one lookup table
            String modelAlias = model.findFirstTable(firstTable.getTableIdentity()).getAlias();
            matchUp = ImmutableMap.of(firstTable.getAlias(), modelAlias);
        } else if (ctx.joins.size() != ctx.allTableScans.size() - 1) {
            // has hanging tables
            throw new IllegalStateException("Please adjust the sequence of join tables. " + toErrorMsg(ctx));
        } else {
            // normal big joins
            if (ctx.joinsTree == null) {
                ctx.joinsTree = new JoinsTree(firstTable, ctx.joins);
            }
            matchUp = ctx.joinsTree.matches(model.getJoinsTree(), result);
        }

        if (matchUp == null)
            return null;

        result.putAll(matchUp);

        return result;
    }

    private static Map<DataModelDesc, Set<IRealization>> makeOrderedModelMap(OLAPContext context) {
        OLAPContext first = context;
        KylinConfig kylinConfig = first.olapSchema.getConfig();
        String projectName = first.olapSchema.getProjectName();
        String factTableName = first.firstTableScan.getOlapTable().getTableName();
        Set<IRealization> realizations = ProjectManager.getInstance(kylinConfig).getRealizationsByTable(projectName, factTableName);

        final Map<DataModelDesc, Set<IRealization>> models = Maps.newHashMap();
        final Map<DataModelDesc, RealizationCost> costs = Maps.newHashMap();
        for (IRealization real : realizations) {
            if (real.isReady() == false)
                continue;
            if (containsAll(real.getAllColumnDescs(), first.allColumns) == false)
                continue;
            if (RemoveBlackoutRealizationsRule.accept(real) == false)
                continue;

            RealizationCost cost = new RealizationCost(real);
            DataModelDesc m = real.getModel();
            Set<IRealization> set = models.get(m);
            if (set == null) {
                set = Sets.newHashSet();
                set.add(real);
                models.put(m, set);
                costs.put(m, cost);
            } else {
                set.add(real);
                RealizationCost curCost = costs.get(m);
                if (cost.compareTo(curCost) < 0)
                    costs.put(m, cost);
            }
        }

        // order model by cheapest realization cost
        TreeMap<DataModelDesc, Set<IRealization>> result = Maps.newTreeMap(new Comparator<DataModelDesc>() {
            @Override
            public int compare(DataModelDesc o1, DataModelDesc o2) {
                RealizationCost c1 = costs.get(o1);
                RealizationCost c2 = costs.get(o2);
                int comp = c1.compareTo(c2);
                if (comp == 0)
                    comp = o1.getName().compareTo(o2.getName());
                return comp;
            }
        });
        result.putAll(models);

        return result;
    }

    private static boolean containsAll(Set<ColumnDesc> allColumnDescs, Set<TblColRef> allColumns) {
        for (TblColRef col : allColumns) {
            if (allColumnDescs.contains(col.getColumnDesc()) == false)
                return false;
        }
        return true;
    }

    private static void fixModel(OLAPContext context, DataModelDesc model, Map<String, String> aliasMap) {
        for (OLAPTableScan tableScan : context.allTableScans) {
            tableScan.fixColumnRowTypeWithModel(model, aliasMap);
        }
    }

    private static void unfixModel(OLAPContext context) {
        for (OLAPTableScan tableScan : context.allTableScans) {
            tableScan.unfixColumnRowTypeWithModel();
        }
    }

    private static class RealizationCost implements Comparable<RealizationCost> {
        final public int priority;
        final public int cost;

        public RealizationCost(IRealization real) {
            // ref Candidate.PRIORITIES
            this.priority = Candidate.PRIORITIES.get(real.getType());

            // ref CubeInstance.getCost()
            int c = real.getAllDimensions().size() * CubeInstance.COST_WEIGHT_DIMENSION + real.getMeasures().size() * CubeInstance.COST_WEIGHT_MEASURE;
            for (JoinTableDesc join : real.getModel().getJoinTables()) {
                if (join.getJoin().isInnerJoin())
                    c += CubeInstance.COST_WEIGHT_INNER_JOIN;
            }
            this.cost = c;
        }

        @Override
        public int compareTo(RealizationCost o) {
            int comp = this.priority - o.priority;
            if (comp != 0)
                return comp;
            else
                return this.cost - o.cost;
        }
    }
}
