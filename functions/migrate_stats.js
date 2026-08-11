/**
 * 마이그레이션 스크립트
 * users/{userId}/훈련기록/{sessionId}/투구별기록  → batting_stats/{userId}
 * users/{userId}/수비훈련기록/{sessionId}/포구별기록 → defense_stats/{userId}
 *
 * 실행: node migrate_stats.js
 */

const admin = require("firebase-admin");
const serviceAccount = require("./service-account-key.json");

admin.initializeApp({ credential: admin.credential.cert(serviceAccount) });

const db = admin.firestore();
const MAX_RECENT = 80;

async function migrateBatting(userId) {
  const sessionsSnap = await db
    .collection("users").doc(userId).collection("훈련기록")
    .orderBy("생성일시", "asc")
    .get();

  if (sessionsSnap.empty) {
    console.log(`[타격] ${userId}: 훈련기록 없음, 건너뜀`);
    return;
  }

  let totalPitches = 0, hitCount = 0, foulCount = 0, strikeCount = 0;
  let baseCorrectCount = 0, reactionSumMs = 0, reactionSampleCount = 0;
  const allPitches = [];

  for (const sessionDoc of sessionsSnap.docs) {
    const sessionDateMs = sessionDoc.get("생성일시")?.toMillis?.() ?? Date.now();

    const pitchesSnap = await db
      .collection("users").doc(userId)
      .collection("훈련기록").doc(sessionDoc.id)
      .collection("투구별기록")
      .orderBy("투구번호", "asc")
      .get();

    for (const p of pitchesSnap.docs) {
      const data = p.data();
      const judgment = (data["판정"] ?? "").replace("(무스윙)", "").trim();
      const baseCorrect = data["베이스정답여부"] ?? false;
      const reactionMs = data["주루반응속도"] ?? null;

      totalPitches++;
      if (judgment === "정타") hitCount++;
      else if (judgment === "파울") foulCount++;
      else if (judgment.startsWith("스트라이크")) strikeCount++;
      if (baseCorrect) baseCorrectCount++;
      if (typeof reactionMs === "number" && reactionMs > 0) {
        reactionSumMs += reactionMs;
        reactionSampleCount++;
      }

      allPitches.push({
        result: judgment,
        baseCorrect,
        reactionMs: typeof reactionMs === "number" ? reactionMs : -1,
        dateMs: sessionDateMs,
      });
    }
  }

  await db.collection("batting_stats").doc(userId).set({
    totalPitches, hitCount, foulCount, strikeCount,
    baseCorrectCount, reactionSumMs, reactionSampleCount,
    recentPitches: allPitches.slice(-MAX_RECENT),
    updatedAt: admin.firestore.Timestamp.now(),
  });

  console.log(`[타격] ${userId}: 세션 ${sessionsSnap.size}개, 총 ${totalPitches}구 완료`);
}

async function migrateDefense(userId) {
  const sessionsSnap = await db
    .collection("users").doc(userId).collection("수비훈련기록")
    .orderBy("생성일시", "asc")
    .get();

  if (sessionsSnap.empty) {
    console.log(`[수비] ${userId}: 수비훈련기록 없음, 건너뜀`);
    return;
  }

  let totalAttempts = 0, successCount = 0;
  let reactionSumMs = 0, reactionSampleCount = 0;
  const allCatches = [];

  for (const sessionDoc of sessionsSnap.docs) {
    const sessionDateMs = sessionDoc.get("생성일시")?.toMillis?.() ?? Date.now();

    const catchesSnap = await db
      .collection("users").doc(userId)
      .collection("수비훈련기록").doc(sessionDoc.id)
      .collection("포구별기록")
      .get();

    // 포구별기록이 없으면 세션 종합결과에서 집계
    if (catchesSnap.empty) {
      const summary = sessionDoc.get("종합결과");
      if (summary) {
        const s = Number(summary["성공횟수"] ?? 0);
        const f = Number(summary["실패횟수"] ?? 0);
        const avg = Number(summary["평균반응속도"] ?? 0);
        totalAttempts += s + f;
        successCount += s;
        if (avg > 0) {
          reactionSumMs += avg * s;
          reactionSampleCount += s;
        }
      }
      continue;
    }

    for (const c of catchesSnap.docs) {
      const data = c.data();
      const result = data["결과"] ?? "";
      const reactionMs = data["반응속도"] ?? null;
      const success = result === "성공";

      totalAttempts++;
      if (success) successCount++;
      if (typeof reactionMs === "number" && reactionMs > 0) {
        reactionSumMs += reactionMs;
        reactionSampleCount++;
      }

      allCatches.push({
        success,
        reactionMs: typeof reactionMs === "number" ? reactionMs : -1,
        dateMs: sessionDateMs,
      });
    }
  }

  await db.collection("defense_stats").doc(userId).set({
    totalAttempts, successCount, reactionSumMs, reactionSampleCount,
    recentCatches: allCatches.slice(-MAX_RECENT),
    updatedAt: admin.firestore.Timestamp.now(),
  });

  console.log(`[수비] ${userId}: 세션 ${sessionsSnap.size}개, 총 ${totalAttempts}회 완료`);
}

async function main() {
  const usersSnap = await db.collection("users").get();
  const userIds = usersSnap.docs.map((d) => d.id);
  console.log(`유저 ${userIds.length}명: ${userIds.join(", ")}`);

  for (const userId of userIds) {
    await migrateBatting(userId);
    await migrateDefense(userId);
  }

  console.log("\n전체 마이그레이션 완료!");
  process.exit(0);
}

main().catch((e) => { console.error(e); process.exit(1); });
