const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or write glossary or history", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("glossary").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("history").get());
});

test("Authenticated user can create, read, list, update, and delete their own glossary entry", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const docRef = aliceDb.collection("users").doc(ALICE_UID).collection("glossary").doc("term_1");
  const now = new Date(Date.now() - 1000);

  await assertSucceeds(
    docRef.set({
      userId: ALICE_UID,
      sourceTerm: "성진우",
      targetTerm: "Sung Jin-Woo",
      sourceLanguage: "KO",
      seriesTitle: "Solo Leveling",
      category: "Character",
      notes: "Main protagonist, S-rank Shadow Monarch",
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(docRef.get());
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("glossary")
      .where("userId", "==", ALICE_UID)
      .get()
  );

  await assertSucceeds(
    docRef.update({
      targetTerm: "Sung Jinwoo",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  await assertSucceeds(docRef.delete());
});

test("Cross-user access to glossary and history is rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date(Date.now() - 1000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("history").doc("hist_1").set({
      userId: ALICE_UID,
      pageTitle: "Chapter 142 - Raw Scan",
      sourceLanguage: "JA",
      sourceType: "ScreenOverlay",
      bubbleCount: 4,
      rawPreview: "領域展開！",
      translatedPreview: "Domain Expansion!",
      bubblesPayload: "[]",
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("history").doc("hist_1").get()
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("history")
      .where("userId", "==", ALICE_UID)
      .get()
  );
});

test("Shadow update with ghost field or invalid enum is rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const docRef = aliceDb.collection("users").doc(ALICE_UID).collection("glossary").doc("term_bad");
  const now = new Date(Date.now() - 1000);

  await assertFails(
    docRef.set({
      userId: ALICE_UID,
      sourceTerm: "領域展開",
      targetTerm: "Domain Expansion",
      sourceLanguage: "JA",
      seriesTitle: "Jujutsu Kaisen",
      category: "Skill",
      isVerifiedAdmin: true,
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertFails(
    docRef.set({
      userId: ALICE_UID,
      sourceTerm: "Bonjour",
      targetTerm: "Hello",
      sourceLanguage: "FR",
      seriesTitle: "Test",
      category: "Skill",
      createdAt: now,
      updatedAt: now,
    })
  );
});
