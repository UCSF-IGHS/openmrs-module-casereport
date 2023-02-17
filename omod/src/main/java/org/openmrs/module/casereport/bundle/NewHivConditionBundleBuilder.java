package org.openmrs.module.casereport.bundle;

import org.apache.commons.lang.StringUtils;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.codesystems.ConditionClinical;
import org.hl7.fhir.r4.model.codesystems.ConditionVerStatus;
import org.openmrs.Concept;
import org.openmrs.api.context.Context;
import org.openmrs.module.casereport.CaseReportConstants;
import org.openmrs.module.casereport.CaseReportUtil;
import org.openmrs.module.fhir2.api.translators.ConceptTranslator;
import org.openmrs.scheduler.TaskDefinition;

public class NewHivConditionBundleBuilder {

	public static Condition processNewHivCondition(Bundle bundle, Composition.SectionComponent sectionComponent,
			String caseReportTriggerName, Observation observation) {
		Condition condition = new Condition();
		condition.setId(observation.getIdElement().getValue());
		condition.setSubject(observation.getSubject());
		if (observation.hasEncounter()) {
			condition.setEncounter(observation.getEncounter());
		}
		condition.setClinicalStatus(new CodeableConcept(new Coding().setCode(ConditionClinical.ACTIVE.toCode())));
		condition.setVerificationStatus(new CodeableConcept(new Coding().setCode(ConditionVerStatus.CONFIRMED.toCode())));
		condition.setRecordedDate(observation.getIssued());

		TaskDefinition taskDefinition = Context.getSchedulerService().getTaskByName(caseReportTriggerName);
		String conceptMap = taskDefinition.getProperty(CaseReportConstants.CONCEPT_TASK_PROPERTY);
		if (!StringUtils.isBlank(conceptMap)) {
			Concept concept = CaseReportUtil.getConceptByMappingString(conceptMap, false);
			if (concept != null) {
				ConceptTranslator conceptTranslator = Context.getRegisteredComponents(ConceptTranslator.class).get(0);
				condition.setCode(conceptTranslator.toFhirResource(concept));
			}
		} else {
			condition.setCode(observation.getValueCodeableConcept());
		}

		String conditionFullUrl = "Condition/" + condition.getIdElement().getValue();
		bundle.addEntry().setFullUrl(conditionFullUrl).setResource(condition).getRequest().setUrl("Condition")
				.setMethod(Bundle.HTTPVerb.POST);
		sectionComponent.addEntry(new Reference(conditionFullUrl));

		return condition;
	}
}
