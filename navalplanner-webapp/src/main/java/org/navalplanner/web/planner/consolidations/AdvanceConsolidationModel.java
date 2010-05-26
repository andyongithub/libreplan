/*
 * This file is part of NavalPlan
 *
 * Copyright (C) 2009 Fundación para o Fomento da Calidade Industrial e
 *                    Desenvolvemento Tecnolóxico de Galicia
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.navalplanner.web.planner.consolidations;

import static org.navalplanner.web.I18nHelper._;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;

import org.joda.time.LocalDate;
import org.navalplanner.business.advance.entities.AdvanceMeasurement;
import org.navalplanner.business.advance.entities.DirectAdvanceAssignment;
import org.navalplanner.business.advance.entities.IndirectAdvanceAssignment;
import org.navalplanner.business.orders.entities.OrderElement;
import org.navalplanner.business.planner.daos.ITaskElementDAO;
import org.navalplanner.business.planner.entities.DayAssignment;
import org.navalplanner.business.planner.entities.ResourceAllocation;
import org.navalplanner.business.planner.entities.SpecificResourceAllocation;
import org.navalplanner.business.planner.entities.Task;
import org.navalplanner.business.planner.entities.TaskElement;
import org.navalplanner.business.planner.entities.ResourceAllocation.DetachDayAssignmentOnRemoval;
import org.navalplanner.business.planner.entities.consolidations.CalculatedConsolidatedValue;
import org.navalplanner.business.planner.entities.consolidations.CalculatedConsolidation;
import org.navalplanner.business.planner.entities.consolidations.ConsolidatedValue;
import org.navalplanner.business.planner.entities.consolidations.Consolidation;
import org.navalplanner.business.planner.entities.consolidations.NonCalculatedConsolidatedValue;
import org.navalplanner.business.planner.entities.consolidations.NonCalculatedConsolidation;
import org.navalplanner.business.planner.entities.consolidations.PendingConsolidatedHoursPerResourceAllocation;
import org.navalplanner.web.planner.order.PlanningState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zkoss.ganttz.extensions.IContextWithPlannerTask;

/**
 * Model for UI operations related to {@link Task}
 * @author Susana Montes Pedreira <smontes@wirelessgalicia.com>
 */
@Service
@Scope(BeanDefinition.SCOPE_PROTOTYPE)
public class AdvanceConsolidationModel implements IAdvanceConsolidationModel {

    @Autowired
    private ITaskElementDAO taskElementDAO;

    private Task task;

    private PlanningState planningState;

    private IContextWithPlannerTask<TaskElement> context;

    private Consolidation consolidation;

    private DirectAdvanceAssignment spreadAdvance;

    private OrderElement orderElement;

    private Date firstConsolidatedDate;

    private String info_message;

    private List<AdvanceConsolidationDTO> consolidationDTOs = new ArrayList<AdvanceConsolidationDTO>();

    private void initConsolidatedDates() {
        consolidationDTOs = AdvanceConsolidationDTO
                .sortByDate(getConsolidationDTOs());
        initLastConsolidatedDate();
        initLastConsolidatedAndSavedDate();
    }

    private boolean containsAdvance(AdvanceMeasurement advanceMeasurement) {
        for (AdvanceConsolidationDTO dto : consolidationDTOs) {
            if (dto.getDate().compareTo(
                    advanceMeasurement.getDate().toDateTimeAtStartOfDay()
                            .toDate()) == 0) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void initLastConsolidatedDate() {
        // init the lastConsolidatedDate
        LocalDate consolidatedUntil = (task.getConsolidation() == null) ? null
                : task.getConsolidation().getConsolidatedUntil();
        AdvanceConsolidationDTO.lastConsolidatedDate = (consolidatedUntil == null) ? null
                : consolidatedUntil.toDateTimeAtStartOfDay().toDate();
    }

    private void initLastConsolidatedAndSavedDate() {
        // init the lastConsolidatedAndSaveDate
        int i = 0;
        AdvanceConsolidationDTO.lastConsolidatedAndSavedDate = null;
        while ((i < consolidationDTOs.size())
                && (!consolidationDTOs.get(i).isSavedConsolidatedValue())) {
            i++;
        }
        if(i < consolidationDTOs.size()){
            AdvanceConsolidationDTO.lastConsolidatedAndSavedDate = consolidationDTOs.get(i).getDate();
        }
    }

    @Override
    public void cancel() {

    }

    @Override
    @Transactional
    public void accept() {
        if (context != null && orderElement != null && isVisibleAdvances()) {
            org.zkoss.ganttz.data.Task ganttTask = context.getTask();
            Date previousStartDate = ganttTask.getBeginDate();
            long previousLength = ganttTask.getLengthMilliseconds();

            createConsolidationIfNeeded();

            for (AdvanceConsolidationDTO dto : consolidationDTOs) {
                if (dto.isConsolidated()) {
                    addConsolidationIfIsNeeded(dto);
                } else {
                    deleteConsolidationIfIsNeeded(dto);
                }
            }

            ganttTask.fireChangesForPreviousValues(previousStartDate,
                    previousLength);
            context.reloadCharts();
        }
    }

    private void createConsolidationIfNeeded() {
        if (consolidation == null && task != null) {
            if (advanceIsCalculated()) {
                IndirectAdvanceAssignment indirectAdvanceAssignment = getIndirecAdvanceAssignment();
                consolidation = CalculatedConsolidation.create(task,
                        indirectAdvanceAssignment);
            } else {
                consolidation = NonCalculatedConsolidation.create(task,
                        spreadAdvance);
            }
            task.setConsolidation(consolidation);
        }
    }

    private IndirectAdvanceAssignment getIndirecAdvanceAssignment() {
        if (orderElement != null) {
            Set<IndirectAdvanceAssignment> indirects = orderElement
                    .getIndirectAdvanceAssignments();
            for (IndirectAdvanceAssignment indirectAdvanceAssignment : indirects) {
                if (indirectAdvanceAssignment.getReportGlobalAdvance()) {
                    return indirectAdvanceAssignment;
                }
            }
        }
        return null;
    }

    private void addConsolidationIfIsNeeded(AdvanceConsolidationDTO dto) {
        if (dto.getConsolidatedValue() == null) {
            ConsolidatedValue consolidatedValue = createNewConsolidatedValue(dto);
            dto.setConsolidatedValue(consolidatedValue);
        }
        addConsolidatedValue(dto.getConsolidatedValue());
    }

    private void addConsolidatedValue(ConsolidatedValue value) {
        if (consolidation != null && task != null) {
            if (!consolidation.isCalculated()) {
                ((NonCalculatedConsolidation) consolidation)
                        .addConsolidatedValue((NonCalculatedConsolidatedValue) value);
            } else {
                ((CalculatedConsolidation) consolidation)
                        .addConsolidatedValue((CalculatedConsolidatedValue) value);
            }

            for (PendingConsolidatedHoursPerResourceAllocation pendingConsolidatedHour : value
                    .getPendingConsolidatedHours()) {
                ResourceAllocation<?> resourceAllocation = pendingConsolidatedHour
                        .getResourceAllocation();
                Integer pendingHours = pendingConsolidatedHour
                        .getPendingConsolidatedHours();
                LocalDate startInclusive = value.getDate().plusDays(1);
                LocalDate endExclusive = LocalDate.fromDateFields(task
                        .getEndDate());

                resourceAllocation
                        .setOnDayAssignmentRemoval(new DetachDayAssignmentOnRemoval());

                if (value.getDate().compareTo(
                        LocalDate.fromDateFields(task.getEndDate())) > 0) {
                    LocalDate date = ResourceAllocation.allocating(
                            Arrays.asList(resourceAllocation
                                    .asResourcesPerDayModification())).untilAllocating(
                            pendingHours);
                    task.setEndDate(date.toDateTimeAtStartOfDay().toDate());
                } else {
                    reassign(resourceAllocation, startInclusive, endExclusive,
                            pendingHours);
                }
            }
        }
    }

    private void reassign(ResourceAllocation<?> resourceAllocation,
            LocalDate startInclusive, LocalDate endExclusive,
            Integer pendingHours) {
        if (resourceAllocation instanceof SpecificResourceAllocation) {
            ((SpecificResourceAllocation) resourceAllocation)
                    .allocateKeepingProportions(startInclusive, endExclusive,
                            pendingHours);
        } else {
            resourceAllocation.withPreviousAssociatedResources().onInterval(
                    startInclusive, endExclusive).allocateHours(pendingHours);
        }
    }

    private ConsolidatedValue createNewConsolidatedValue(
            AdvanceConsolidationDTO dto) {
        if (consolidation != null && task != null) {

            Set<PendingConsolidatedHoursPerResourceAllocation> pendingConsolidatedHours = ConsolidatedValue
                    .createPendingConsolidatedHours(LocalDate
                            .fromDateFields(dto.getDate()), dto.getValue(),
                            task.getAllResourceAllocations());

            if (consolidation.isCalculated()) {
                return CalculatedConsolidatedValue.create(LocalDate
                        .fromDateFields(dto.getDate()), dto.getValue(),
                        pendingConsolidatedHours);
            } else {
                return NonCalculatedConsolidatedValue.create(LocalDate
                    .fromDateFields(dto.getDate()), dto.getValue(), dto
                        .getAdvanceMeasurement(), pendingConsolidatedHours);
            }
        }
        return null;
    }

    private void deleteConsolidationIfIsNeeded(AdvanceConsolidationDTO dto) {
        if (dto.getConsolidatedValue() != null) {
            if (consolidation != null && task != null) {
                if (!consolidation.isCalculated()) {
                    ((NonCalculatedConsolidation) consolidation)
                            .getNonCalculatedConsolidatedValues().remove(
                                    dto.getConsolidatedValue());
                } else {
                    ((CalculatedConsolidation) consolidation)
                            .getCalculatedConsolidatedValues().remove(
                                    dto.getConsolidatedValue());
                }

                if (consolidation.getConsolidatedValues().isEmpty()) {
                    consolidation = null;
                    task.setConsolidation(consolidation);
                }

                LocalDate firstDayNotConsolidated = task
                        .getFirstDayNotConsolidated();
                for (DayAssignment dayAssignment : task.getDayAssignments()) {
                    if (dayAssignment.getDay().compareTo(
                            firstDayNotConsolidated) >= 0) {
                        dayAssignment.setConsolidated(false);
                    }
                }

                LocalDate endExclusive = LocalDate.fromDateFields(task
                        .getEndDate());

                if (consolidation == null) {
                    Set<ResourceAllocation<?>> allResourceAllocations = task
                            .getAllResourceAllocations();
                    for (ResourceAllocation<?> resourceAllocation : allResourceAllocations) {
                        resourceAllocation
                                .setOnDayAssignmentRemoval(new DetachDayAssignmentOnRemoval());
                        reassign(resourceAllocation, firstDayNotConsolidated,
                                endExclusive, resourceAllocation
                                        .getOriginalTotalAssigment());
                    }
                } else {
                    Set<PendingConsolidatedHoursPerResourceAllocation> pendingConsolidatedHours = consolidation
                            .getConsolidatedValues().last()
                            .getPendingConsolidatedHours();
                    for (PendingConsolidatedHoursPerResourceAllocation pendingConsolidatedHour : pendingConsolidatedHours) {
                        ResourceAllocation<?> resourceAllocation = pendingConsolidatedHour
                                .getResourceAllocation();
                        reassign(resourceAllocation, firstDayNotConsolidated,
                                endExclusive, pendingConsolidatedHour
                                        .getPendingConsolidatedHours());
                    }
                }
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void initAdvancesFor(Task task,
            IContextWithPlannerTask<TaskElement> context,
            PlanningState planningState) {
        this.context = context;
        initTask(task);
        this.planningState = planningState;
        initAdvanceConsolidationsDTOs(task);
    }

    private void initTask(Task task) {
        this.task = task;
        taskElementDAO.reattach(this.task);
    }

    private void initAdvanceConsolidationsDTOs(Task task) {
        orderElement = task.getOrderElement();
        spreadAdvance = orderElement.getReportGlobalAdvanceAssignment();
        consolidation = task.getConsolidation();
        if (consolidation != null) {
            for (ConsolidatedValue consolidatedValue : consolidation
                    .getConsolidatedValues()) {
                consolidatedValue.getPendingConsolidatedHours().size();
            }
        }

        if (spreadAdvance != null) {
            createAdvanceConsolidationDTOs();
            initConsolidatedDates();
            addNonConsolidatedAdvances();
            setReadOnlyConsolidations();
        }
    }

    private void createAdvanceConsolidationDTOs() {
        consolidationDTOs = new ArrayList<AdvanceConsolidationDTO>();
        if (consolidation != null) {
            if (!consolidation.isCalculated()) {
                for (NonCalculatedConsolidatedValue consolidatedValue : ((NonCalculatedConsolidation) consolidation)
                        .getNonCalculatedConsolidatedValues()) {
                    consolidationDTOs.add(new AdvanceConsolidationDTO(
                            consolidatedValue.getAdvanceMeasurement(),
                            consolidatedValue));
                }
            }else{
                for (CalculatedConsolidatedValue consolidatedValue : ((CalculatedConsolidation) consolidation)
                        .getCalculatedConsolidatedValues()) {
                    consolidationDTOs.add(new AdvanceConsolidationDTO(null,
                            consolidatedValue));
                }
            }
        }
    }

    private void addNonConsolidatedAdvances() {
        int i = 0;
        for (AdvanceMeasurement advance : getAdvances()) {
            if (canBeConsolidateAndShow(advance)) {
                consolidationDTOs.add(new AdvanceConsolidationDTO(advance));
            }
        }
        consolidationDTOs = AdvanceConsolidationDTO
                .sortByDate(consolidationDTOs);
    }

    private boolean canBeConsolidateAndShow(
            AdvanceMeasurement advanceMeasurement) {
        Date date = advanceMeasurement.getDate().toDateTimeAtStartOfDay().toDate();
        return ((AdvanceConsolidationDTO.canBeConsolidateAndShow(date)) && (!containsAdvance(advanceMeasurement)));
    }

    @Override
    public String getInfoAdvanceAssignment() {
        if (this.spreadAdvance == null || this.orderElement == null) {
            return "";
        }
        return getInfoAdvanceAssignment(this.spreadAdvance);
    }

    private String getInfoAdvanceAssignment(DirectAdvanceAssignment assignment) {
        if (assignment == null) {
            return "";
        }
        if (assignment.getMaxValue() == null) {
            return "";
        }
        return _("( max: {0} )", assignment.getMaxValue());
    }

    private List<AdvanceMeasurement> getAdvances() {
        if (spreadAdvance != null) {
            return new ArrayList<AdvanceMeasurement>(spreadAdvance
                    .getAdvanceMeasurements());
        }
        return new ArrayList<AdvanceMeasurement>();
    }

    @Override
    public boolean isVisibleAdvances() {
        return (!isVisibleMessages());
    }

    @Override
    public boolean isVisibleMessages() {
        return ((getAdvances().size() == 0) || (isSubcontrated()) || (!hasResourceAllocation()));
    }

    private boolean advanceIsCalculated(){
        return ((spreadAdvance != null) && (spreadAdvance.isFake()));
    }

    public String infoMessages() {
        if (getAdvances().size() > 0) {
            return _("It is not allowed to consolidate advances.");
        }
        return _("There are not any assigned advance to current task");
    }

    public void setConsolidationDTOs(
            List<AdvanceConsolidationDTO> consolidationDTOs) {
        this.consolidationDTOs = consolidationDTOs;
    }

    public List<AdvanceConsolidationDTO> getConsolidationDTOs() {
        if (spreadAdvance != null && orderElement != null) {
            return consolidationDTOs;
        }
        return new ArrayList<AdvanceConsolidationDTO>();
    }

    private boolean hasResourceAllocation() {
        return ((task != null) && (task.hasResourceAllocations()));
    }

    private boolean isSubcontrated() {
        return ((task != null) && (task.isSubcontracted()));
    }

    public boolean hasLimitingResourceAllocation() {
        return ((task != null) && (task.hasLimitedResourceAllocation()));
    }

    @Override
    public void setReadOnlyConsolidations() {
        // set all advance consolidations as read only
        AdvanceConsolidationDTO.setAllReadOnly(hasLimitingResourceAllocation());
    }

}
