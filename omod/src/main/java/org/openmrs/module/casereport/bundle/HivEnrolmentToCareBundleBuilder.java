package org.openmrs.module.casereport.bundle;

import java.util.Arrays;
import java.util.List;

import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.ReferenceAndListParam;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.EpisodeOfCare;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;
import org.openmrs.Concept;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.casereport.CaseReportConstants;
import org.openmrs.module.casereport.CaseReportUtil;
import org.openmrs.module.casereport.Constants;
import org.openmrs.module.fhir2.api.FhirObservationService;
import org.openmrs.scheduler.TaskDefinition;

public class HivEnrolmentToCareBundleBuilder {

	public static IBundleProvider getObservationRecords(ReferenceAndListParam subjectReference) {
		return BundleBuilderUtil.getObservationRecords(subjectReference, Constants.DATE_ENROLLED_IN_HIV_CARE,
				Constants.HIV_ENROLMENT_TO_CARE_ENCOUNTER_TYPE_UUID);
	}

	public static void buildNewHivRxBundle(Bundle bundle, Composition.SectionComponent sectionComponent, Encounter encounter,
			Observation observation, Patient patient) {
		processEpisodeOfCare(bundle, sectionComponent, encounter, observation, patient);
	}

	private static void processEpisodeOfCare(Bundle bundle, Composition.SectionComponent sectionComponent,
			Encounter encounter, Observation observation, Patient patient) {
		EpisodeOfCare episodeOfCare = new EpisodeOfCare();
		episodeOfCare.setId(observation.getIdElement().getValue());
		episodeOfCare.setStatus(EpisodeOfCare.EpisodeOfCareStatus.ACTIVE);
		episodeOfCare.setPatient(observation.getSubject());

		if (observation.hasValueDateTimeType()) {
			Period enrollmentDatePeriod = new Period();
			enrollmentDatePeriod.setStart(observation.getValueDateTimeType().getValue());
			episodeOfCare.setPeriod(enrollmentDatePeriod);
		}

		org.openmrs.Encounter openMrsEncounter = Context.getEncounterService()
				.getEncounterByUuid(encounter.getIdElement().getValue());
		System.out.println("HivEnrolmentToCareBundleBuilder:openMrsEncounter - " + openMrsEncounter);

		List<Obs> observations = Context.getObsService()
				.getObservations(Arrays.asList(patient.getPerson()), Arrays.asList(openMrsEncounter), null, null,
						null, null, null, null, null, null, null, false,
						null);
		System.out.println("HivEnrolmentToCareBundleBuilder:observations - " + observations);

		Obs enrolmentUniqueId = BundleBuilderUtil.getObsHavingConceptUuid(observations,
				Constants.HIV_ENROLMENT_TO_CARE_UNIQUE_ID_CONCEPT_UUID);
		if (enrolmentUniqueId != null) {
			episodeOfCare.addIdentifier()
					.setSystem("http://openhie.org/fhir/hiv-casereporting/identifier/enrollment-unique-id")
					.setValue(enrolmentUniqueId.getValueText());
		}

		String organizationReference = Context.getAdministrationService()
				.getGlobalProperty(Constants.GP_ORGANIZATION_REFERENCE);
		episodeOfCare.setManagingOrganization(new Reference(organizationReference));

		TaskDefinition taskDefinition = Context.getSchedulerService().getTaskByName(Constants.HIV_CASE_REPORT_TRIGGER_NAME);
		String obsConcept = taskDefinition != null ? taskDefinition.getProperty(CaseReportConstants.OBS_CONCEPT) : "";
		Concept questionConcept = CaseReportUtil.getConceptByMappingString(obsConcept, false);
		String obsValueCoded = taskDefinition.getProperty(CaseReportConstants.OBS_VALUE_CODED);
		Concept answerConcept = CaseReportUtil.getConceptByMappingString(obsValueCoded, false);
		System.out.println("HivEnrolmentToCareBundleBuilder: concept - " + questionConcept);
		List<Obs> hivDiagnosisObservations = Context.getObsService()
				.getObservationsByPersonAndConcept(patient.getPerson(), questionConcept);
		System.out.println("HivEnrolmentToCareBundleBuilder: hivDiagnosisObservations - " + hivDiagnosisObservations);
		Observation hivDiagnosisFhirObs = null;
		if (!hivDiagnosisObservations.isEmpty()) {
			Obs hivDiagnosisObs = null;
			for (Obs o : hivDiagnosisObservations) {
				if (o.getValueCoded() == answerConcept) {
					hivDiagnosisObs = o;
					System.out.println("HivEnrolmentToCareBundleBuilder: hivDiagnosisObs - " + hivDiagnosisObs);
				}
			}

			if (hivDiagnosisObs != null) {
				FhirObservationService fhirObservationService = Context.getRegisteredComponents(FhirObservationService.class)
						.get(0);
				hivDiagnosisFhirObs = fhirObservationService.get(hivDiagnosisObs.getUuid());
				System.out.println("HivEnrolmentToCareBundleBuilder: hivDiagnosisFhirObs - " + hivDiagnosisFhirObs);
			}
		}

		if (hivDiagnosisFhirObs != null) {
			Condition hivDiagnosisCondition = NewHivConditionBundleBuilder.processNewHivCondition(bundle, sectionComponent,
					Constants.HIV_CASE_REPORT_TRIGGER_NAME, hivDiagnosisFhirObs);
			System.out.println("HivEnrolmentToCareBundleBuilder: hivDiagnosisCondition - " + hivDiagnosisCondition);

			String conditionFullUrl = "Condition/" + hivDiagnosisCondition.getIdElement().getValue();
			episodeOfCare.addDiagnosis().setCondition(new Reference(conditionFullUrl));
		}

		String episodeOfCareFullUrl = "EpisodeOfCare/" + episodeOfCare.getIdElement().getValue();
		bundle.addEntry().setFullUrl(episodeOfCareFullUrl).setResource(episodeOfCare).getRequest().setUrl("EpisodeOfCare")
				.setMethod(Bundle.HTTPVerb.POST);
		sectionComponent.addEntry(new Reference(episodeOfCareFullUrl));

	}
}
