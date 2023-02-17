package org.openmrs.module.casereport.bundle;

import java.util.UUID;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.openmrs.Person;
import org.openmrs.api.context.Context;
import org.openmrs.module.fhir2.api.translators.ConceptTranslator;

public class PatientDiedBundleBuilder {

	static FhirContext CTX = FhirContext.forR4();

	public static void buildPatienDiedBundle(Bundle bundle, Person person, Patient patient) {
		String observationUuid = UUID.randomUUID().toString();
		String observationFullUrl = "Observation/" + observationUuid;
		Observation observation = new Observation();
		observation.setId(observationUuid);
		CodeableConcept code = new CodeableConcept();
		code.addCoding().setCode("CAUSE-OF-DEATH").setDisplay("Cause of death")
				.setSystem("http://openhie.org/fhir/hiv-casereporting/CodeSystem/cs-hiv-obs-codes");
		observation.setCode(code);
		observation.setStatus(Observation.ObservationStatus.FINAL);
		observation.setSubject(new Reference("Patient/" + patient.getIdElement().getValue()));
		observation.setEffective(new DateTimeType(person.getDeathDate()));
		if (person.getCauseOfDeath() != null) {
			ConceptTranslator conceptTranslator = Context.getRegisteredComponents(ConceptTranslator.class).get(0);
			observation.setValue(conceptTranslator.toFhirResource(person.getCauseOfDeath()));
		}

		System.out.println(
				"Covid19BundleBuilder:patientDiedObservation - " + CTX.newJsonParser().encodeResourceToString(observation));
		BundleBuilderUtil.addBundleEntry(bundle, observationFullUrl, observation, observationFullUrl, Bundle.HTTPVerb.POST);
	}
}
