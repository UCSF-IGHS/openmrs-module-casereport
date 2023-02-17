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
import org.openmrs.module.fhir2.api.FhirEncounterService;
import org.openmrs.module.fhir2.api.FhirLocationService;
import org.openmrs.module.fhir2.api.FhirPractitionerService;

public class FhirUtils {

	public static void processEncounter(Bundle bundle, Composition.SectionComponent sectionComponent,
			Encounter encounter, FhirEncounterService fhirEncounterService,
			FhirPractitionerService fhirPractitionerService, FhirLocationService fhirLocationService) {
		//Add encounter
		String encounterFullUrl = "Encounter/" + encounter.getIdElement().getValue();

		boolean hasEncounter = bundle.getEntry().stream()
				.filter(bundleEntryComponent -> bundleEntryComponent.getFullUrl().equals(encounterFullUrl))
				.count() > 0;

		if (!hasEncounter) {
			bundle.addEntry().setFullUrl(encounterFullUrl).setResource(encounter).getRequest().setUrl("Encounter")
					.setMethod(Bundle.HTTPVerb.POST);
			sectionComponent.addEntry(new Reference(encounterFullUrl));
		}

		if (encounter.hasPartOf()) {
			String partOfEncounterUuid = org.openmrs.module.fhir2.api.util.FhirUtils.referenceToId(
					encounter.getPartOf().getReference()).orElse("");
			Encounter partOfEncounter = fhirEncounterService.get(partOfEncounterUuid);
			if (partOfEncounter != null) {
				processEncounter(bundle, sectionComponent, partOfEncounter, fhirEncounterService,
						fhirPractitionerService, fhirLocationService);
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
					Practitioner practitioner = fhirPractitionerService.get(practitionerUuid);
					if (practitioner != null) {
						practitionerFullUrl = "Practitioner/" + practitioner.getIdElement().getValue();
						final String pUrl = practitionerFullUrl;

						boolean hasPractitioner = bundle.getEntry().stream()
								.filter(bundleEntryComponent -> bundleEntryComponent.getFullUrl().equals(pUrl))
								.count() > 0;

						if (!hasPractitioner) {
							bundle.addEntry().setFullUrl(practitionerFullUrl).setResource(practitioner).getRequest()
									.setUrl("Practitioner").setMethod(Bundle.HTTPVerb.POST);
							sectionComponent.addEntry(new Reference(practitionerFullUrl));
						}
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
					Location location = fhirLocationService.get(locationUuid);
					if (location != null) {
						locationFullUrl = "Location/" + location.getIdElement().getValue();
						final String lUrl = locationFullUrl;

						boolean hasLocation = bundle.getEntry().stream()
								.filter(bundleEntryComponent -> bundleEntryComponent.getFullUrl().equals(lUrl))
								.count() > 0;

						if (!hasLocation) {
							bundle.addEntry().setFullUrl(locationFullUrl).setResource(location).getRequest()
									.setUrl("Location").setMethod(Bundle.HTTPVerb.POST);
							sectionComponent.addEntry(new Reference(locationFullUrl));
						}
					}
				}
			}
		}
	}
}
