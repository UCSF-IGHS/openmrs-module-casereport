package org.openmrs.module.casereport.bundle;

import java.util.ArrayList;
import java.util.List;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.ReferenceAndListParam;
import ca.uhn.fhir.rest.param.TokenAndListParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.SimpleBundleProvider;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Resource;
import org.openmrs.Obs;
import org.openmrs.api.context.Context;
import org.openmrs.module.fhir2.api.FhirEncounterService;
import org.openmrs.module.fhir2.api.FhirObservationService;
import org.openmrs.module.fhir2.api.search.param.ObservationSearchParams;
import org.openmrs.module.fhir2.api.util.FhirUtils;

public class BundleBuilderUtil {

	static FhirContext CTX = FhirContext.forR4();

	public static Obs getObsHavingConceptUuid(List<Obs> observations, String conceptUuid) {
		return observations.stream().filter(obs -> obs.getConcept().getUuid().equals(conceptUuid))
				.findFirst().orElse(null);
	}

	public static void addBundleEntry(Bundle bundle, String resourceFullUrl, Resource resource, String url,
			Bundle.HTTPVerb method) {
		bundle.addEntry()
				.setFullUrl(resourceFullUrl)
				.setResource(resource)
				.getRequest()
				.setUrl(url)
				.setMethod(method);
	}

	public static IBundleProvider getObservationRecords(ReferenceAndListParam subjectReference, String questionConceptUuid,
			String encounterTypeUuid) {
		TokenAndListParam conceptTokenAndListParam = new TokenAndListParam();
		TokenParam conceptTokenParam = new TokenParam(questionConceptUuid);
		conceptTokenAndListParam.addAnd(conceptTokenParam);

		FhirObservationService fhirObservationService = Context.getRegisteredComponents(FhirObservationService.class).get(0);
		FhirEncounterService fhirEncounterService = Context.getRegisteredComponents(FhirEncounterService.class).get(0);

		ObservationSearchParams observationSearchParams = new ObservationSearchParams();
		observationSearchParams.setPatient(subjectReference);
		observationSearchParams.setCode(conceptTokenAndListParam);

		IBundleProvider observationRecords = fhirObservationService.searchForObservations(observationSearchParams);

		List<IBaseResource> observations = new ArrayList<>();
		if (observationRecords.size() > 0) {
			for (int idx = 0; idx < observationRecords.size(); idx++) {
				Observation observation = (Observation) observationRecords.getResources(idx, idx).get(0);
				System.out.println(
						"observation: " + CTX.newJsonParser().setPrettyPrint(true).encodeResourceToString(observation));
				if (observation.hasEncounter()) {
					String encounterUuid = FhirUtils.referenceToId(observation.getEncounter().getReference()).orElse("");
					Encounter encounter = fhirEncounterService.get(encounterUuid);
					System.out.println(
							"encounter: " + CTX.newJsonParser().setPrettyPrint(true).encodeResourceToString(encounter));
					if (encounter != null && encounter.hasType() && encounter.getType().size() > 0) {
						for (CodeableConcept codeableConcept : encounter.getType()) {
							System.out.println("codeableConcept: " + codeableConcept);
							for (Coding coding : codeableConcept.getCoding()) {
								System.out.println("coding: " + coding.getCode());
								if (coding.hasCode() && coding.getCode().equals(encounterTypeUuid)) {
									System.out.println(
											coding.getCode() + " equals " + encounterTypeUuid);
									observations.add(observation);
								}
							}
						}
					}
				}
			}
		}
		return new SimpleBundleProvider(observations);
	}
}
