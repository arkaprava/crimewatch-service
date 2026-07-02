// Initialises the crime_info_service database: creates the crime_incidents
// collection with JSON schema validation and the indexes declared on the
// CrimeIncident entity. Idempotent; safe to re-run.
//
//   docker exec -i crime-info-mongodb mongosh --quiet < infra/mongo-init.js

db = db.getSiblingDB("crime_info_service");

const validator = {
	$jsonSchema: {
		bsonType: "object",
		required: ["title", "crimeType", "severity", "status", "location", "occurredAt"],
		properties: {
			source: { bsonType: ["string", "null"], maxLength: 100 },
			externalId: { bsonType: ["string", "null"], maxLength: 255 },
			title: { bsonType: "string", minLength: 1, maxLength: 255 },
			description: { bsonType: ["string", "null"], maxLength: 2000 },
			crimeType: {
				enum: ["THEFT", "BURGLARY", "ROBBERY", "ASSAULT", "HOMICIDE", "KIDNAPPING",
					"VANDALISM", "FRAUD", "CYBERCRIME", "DRUG_OFFENSE", "ARSON", "OTHER"]
			},
			severity: { enum: ["LOW", "MEDIUM", "HIGH", "CRITICAL"] },
			status: {
				enum: ["REPORTED", "UNDER_INVESTIGATION", "ARREST_MADE", "CLOSED", "UNSOLVED"]
			},
			location: {
				bsonType: "object",
				required: ["city", "country"],
				properties: {
					address: { bsonType: ["string", "null"], maxLength: 255 },
					city: { bsonType: "string", minLength: 1, maxLength: 100 },
					state: { bsonType: ["string", "null"], maxLength: 100 },
					country: { bsonType: "string", minLength: 1, maxLength: 100 },
					postalCode: { bsonType: ["string", "null"], maxLength: 20 }
				}
			},
			occurredAt: { bsonType: "date" },
			reportedAt: { bsonType: ["date", "null"] },
			createdAt: { bsonType: ["date", "null"] },
			updatedAt: { bsonType: ["date", "null"] }
		}
	}
};

if (!db.getCollectionNames().includes("crime_incidents")) {
	db.createCollection("crime_incidents", { validator: validator, validationLevel: "moderate" });
	print("Created collection crime_incidents");
} else {
	db.runCommand({ collMod: "crime_incidents", validator: validator, validationLevel: "moderate" });
	print("Updated validator on crime_incidents");
}

const incidents = db.getCollection("crime_incidents");
incidents.createIndex({ source: 1, externalId: 1 },
	{ name: "source_external_id_idx", unique: true, sparse: true });
incidents.createIndex({ crimeType: 1 }, { name: "crime_type_idx" });
incidents.createIndex({ status: 1 }, { name: "status_idx" });
incidents.createIndex({ occurredAt: 1 }, { name: "occurred_at_idx" });
incidents.createIndex({ geo_coordinates: "2dsphere" }, { name: "geo_coordinates_2dsphere_idx" });

print("Indexes:");
incidents.getIndexes().forEach(idx => print("  " + idx.name));
