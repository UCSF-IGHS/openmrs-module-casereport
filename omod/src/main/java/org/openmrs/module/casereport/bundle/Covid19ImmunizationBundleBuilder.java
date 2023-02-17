package org.openmrs.module.casereport.bundle;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Immunization;
import org.hl7.fhir.r4.model.Observation;
import org.openmrs.Obs;
import org.openmrs.Person;
import org.openmrs.api.context.Context;
import org.openmrs.module.casereport.Constants;

public class Covid19ImmunizationBundleBuilder {

	static FhirContext CTX = FhirContext.forR4();

	public static void buildImmunizationBundle(Bundle bundle, Encounter encounter, Observation observation, Person person) {
		String immunizationUuid = UUID.randomUUID().toString();
		String immunizationFullUrl = "Immunization/" + immunizationUuid;
		Immunization immunization = new Immunization();
		immunization.setId(immunizationUuid);
		immunization.setStatus(Immunization.ImmunizationStatus.COMPLETED);
		immunization.setVaccineCode(observation.getValueCodeableConcept());
		immunization.setPatient(encounter.getSubject());
		immunization.setEncounterTarget(encounter);

		String encounterUuid = encounter.getId();
		org.openmrs.Encounter openMrsEncounter = Context.getEncounterService().getEncounterByUuid(encounterUuid);
		List<Obs> observations = Context.getObsService()
				.getObservations(Arrays.asList(person), Arrays.asList(openMrsEncounter),
						null, null, null, null, null, null, null, null,
						null, false, null);
		Obs vaccinationDate = BundleBuilderUtil.getObsHavingConceptUuid(observations,
				Constants.COVID19_VACCINATION_DATE_CONCEPT_UUID);
		if (vaccinationDate != null) {
			immunization.setOccurrence(new DateTimeType(vaccinationDate.getValueDatetime()));
		}

		System.out.println(
				"Covid19ImmunizationBundleBuilder:immunization - " + CTX.newJsonParser()
						.encodeResourceToString(immunization));
		BundleBuilderUtil.addBundleEntry(bundle, immunizationFullUrl, immunization, immunizationFullUrl,
				Bundle.HTTPVerb.POST);
	}

}
