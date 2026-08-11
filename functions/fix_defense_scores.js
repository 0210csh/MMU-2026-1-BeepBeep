const admin = require('firebase-admin');
const serviceAccount = require('./service-account-key.json');

admin.initializeApp({ credential: admin.credential.cert(serviceAccount) });
const db = admin.firestore();

const DEFENSE_MIN_MS = 5000.0;
const DEFENSE_MAX_MS = 40000.0;

function reactionScore(avgMs) {
  if (avgMs == null) return 0.0;
  const score = ((DEFENSE_MAX_MS - avgMs) / (DEFENSE_MAX_MS - DEFENSE_MIN_MS)) * 100.0;
  return Math.min(100.0, Math.max(0.0, score));
}

async function main() {
  const quarters = ['2026Q1', '2026Q2', '2026Q3', '2026Q4'];

  for (const quarter of quarters) {
    const entriesSnap = await db.collection(`rankings_defense/${quarter}/entries`).get();
    if (entriesSnap.empty) { console.log(`[${quarter}] 문서 없음, 건너뜀`); continue; }

    console.log(`[${quarter}] ${entriesSnap.size}개 문서 처리 중...`);
    for (const doc of entriesSnap.docs) {
      const data = doc.data();
      const attempts    = data.attemptCount || 0;
      const successes   = data.successCount || 0;
      const reactionSum = data.reactionSumMs || 0;
      const reactionCnt = data.reactionSampleCount || 0;

      const rate        = attempts > 0 ? (successes / attempts) * 100.0 : 0.0;
      const avgReaction = reactionCnt > 0 ? reactionSum / reactionCnt : null;
      const reactScore  = reactionScore(avgReaction);
      const score       = Math.min(100.0, Math.max(0.0, rate * 0.6 + reactScore * 0.4));

      await doc.ref.update({ score });
      console.log(`[${quarter}] ${doc.id}: 성공률 ${rate.toFixed(1)}% + 반응속도 ${reactScore.toFixed(1)}점 → score ${score.toFixed(2)}`);
    }
  }

  console.log('\n완료!');
  process.exit(0);
}

main().catch(e => { console.error(e); process.exit(1); });
