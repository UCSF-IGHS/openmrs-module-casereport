/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.casereport;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Reference;
import org.openmrs.api.context.Context;
import org.openmrs.module.fhir2.api.FhirEncounterService;
import org.openmrs.module.fhir2.api.FhirLocationService;
import org.openmrs.module.fhir2.api.FhirPractitionerService;

public class FhirUtils {

	public static void processEncounter(Bundle bundle, Composition.SectionComponent sectionComponent,
			Encounter encounter) {
		//Add encounter
		String encounterFullUrl;

		encounterFullUrl = "Encounter/" + encounter.getIdElement().getValue();
		bundle.addEntry().setFullUrl(encounterFullUrl).setResource(encounter).getRequest().setUrl("Encounter")
				.setMethod(Bundle.HTTPVerb.POST);
		sectionComponent.addEntry(new Reference(encounterFullUrl));

		if (encounter.hasPartOf()) {
			String partOfEncounterUuid = org.openmrs.module.fhir2.api.util.FhirUtils.referenceToId(
					encounter.getPartOf().getReference()).orElse("");
			FhirEncounterService encounterService = Context.getService(FhirEncounterService.class);
			Encounter partOfEncounter = encounterService.get(partOfEncounterUuid);
			if (partOfEncounter != null) {
				processEncounter(bundle, sectionComponent, partOfEncounter);
			}
		}

		//Add Encounter participants
		if (encounter.hasParticipant()) {
			String practitionerFullUrl;
			for (Encounter.EncounterParticipantComponent encounterParticipant : encounter.getParticipant()) {
				if (encounterParticipant.hasIndividual()) {
					String practitionerUuid = org.openmrs.module.fhir2.api.util.FhirUtils.referenceToId(
									encounterParticipant.getIndividual().getReference())
							.orElse("");
					FhirPractitionerService fhirPractitionerService = Context.getService(FhirPractitionerService.class);
					Practitioner practitioner = fhirPractitionerService.get(practitionerUuid);
					if (practitioner != null) {
						practitionerFullUrl = "Practitioner/" + practitioner.getIdElement().getValue();
						bundle.addEntry().setFullUrl(practitionerFullUrl).setResource(practitioner).getRequest()
								.setUrl("Practitioner").setMethod(Bundle.HTTPVerb.POST);
						sectionComponent.addEntry(new Reference(practitionerFullUrl));
					}
				}
			}
		}

		//Add Encounter locations
		if (encounter.hasLocation()) {
			String locationFullUrl;
			for (Encounter.EncounterLocationComponent encounterLocation : encounter.getLocation()) {
				if (encounterLocation.hasLocation()) {
					String locationUuid = org.openmrs.module.fhir2.api.util.FhirUtils.referenceToId(
							encounterLocation.getLocation().getReference()).orElse("");
					FhirLocationService fhirLocationService = Context.getService(FhirLocationService.class);
					Location location = fhirLocationService.get(locationUuid);
					if (location != null) {
						locationFullUrl = "Location/" + location.getIdElement().getValue();
						bundle.addEntry().setFullUrl(locationFullUrl).setResource(location).getRequest()
								.setUrl("Location").setMethod(Bundle.HTTPVerb.POST);
						sectionComponent.addEntry(new Reference(locationFullUrl));
					}
				}
			}
		}
	}
}
