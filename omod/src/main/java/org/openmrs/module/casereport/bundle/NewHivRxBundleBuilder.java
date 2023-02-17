package org.openmrs.module.casereport.bundle;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CarePlan;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Period;
import org.openmrs.Obs;
import org.openmrs.Person;
import org.openmrs.api.context.Context;
import org.openmrs.module.casereport.Constants;
import org.openmrs.module.fhir2.api.translators.ConceptTranslator;

public class NewHivRxBundleBuilder {

	static FhirContext CTX = FhirContext.forR4();

	public static void buildNewHivRxBundle(Bundle bundle, Encounter encounter, Observation observation, Person person) {
		String carePlanUuid = UUID.randomUUID().toString();
		String carePlanFullUrl = "CarePlan/" + carePlanUuid;
		CarePlan carePlan = new CarePlan();
		carePlan.setId(carePlanUuid);
		carePlan.setStatus(CarePlan.CarePlanStatus.ACTIVE);
		carePlan.setIntent(CarePlan.CarePlanIntent.PLAN);
		carePlan.setSubject(observation.getSubject());

		String uuid = encounter.getIdElement().getValue();
		org.openmrs.Encounter openMrsEncounter = Context.getEncounterService().getEncounterByUuid(uuid);
		List<Obs> observations = Context.getObsService()
				.getObservations(Arrays.asList(person), Arrays.asList(openMrsEncounter), null, null, null,
						null, null, null, null, null, null, false, null);
		Obs dateOfArtInitiation = BundleBuilderUtil.getObsHavingConceptUuid(observations,
				Constants.DATE_OF_ART_INITIATION_CONCEPT_UUID);
		if (dateOfArtInitiation != null) {
			Period period = new Period();
			period.setStart(dateOfArtInitiation.getValueDatetime());
			carePlan.setPeriod(period);
		}

		ConceptTranslator conceptTranslator = Context.getRegisteredComponents(ConceptTranslator.class).get(0);

		CarePlan.CarePlanActivityDetailComponent activityDetail = new CarePlan.CarePlanActivityDetailComponent();
		Obs artRegimenLine = BundleBuilderUtil.getObsHavingConceptUuid(observations, Constants.REGIMEN_LINE_CONCEPT_UUID);
		if (artRegimenLine != null) {
			activityDetail.addExtension()
					.setUrl("http://openhie.org/fhir/hiv-casereporting/StructureDefinition/art-regimen-line")
					.setValue(conceptTranslator.toFhirResource(artRegimenLine.getValueCoded()));
		}
		activityDetail.setStatus(CarePlan.CarePlanActivityStatus.INPROGRESS);
		activityDetail.setKind(CarePlan.CarePlanActivityKind.MEDICATIONREQUEST);
		Obs artRegimen = BundleBuilderUtil.getObsHavingConceptUuid(observations, Constants.REGIMEN_CONCEPT_UUID);
		activityDetail.setCode(conceptTranslator.toFhirResource(artRegimen.getConcept()));
		activityDetail.setProduct(conceptTranslator.toFhirResource(artRegimen.getValueCoded()));

		CarePlan.CarePlanActivityComponent activity = new CarePlan.CarePlanActivityComponent();
		activity.addOutcomeCodeableConcept();
		activity.setDetail(activityDetail);
		carePlan.addActivity(activity);

		System.out.println(
				"NewHivRxBundleBuilder:carePlan - " + CTX.newJsonParser().encodeResourceToString(carePlan));
		BundleBuilderUtil.addBundleEntry(bundle, carePlanFullUrl, carePlan, carePlanFullUrl, Bundle.HTTPVerb.POST);
	}
}
