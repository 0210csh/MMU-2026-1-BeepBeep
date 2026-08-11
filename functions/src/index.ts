import { initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { onDocumentUpdated } from "firebase-functions/v2/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { logger } from "firebase-functions/v2";

initializeApp();
const db = getFirestore();
const messaging = getMessaging();

const SESSIONS = "training_sessions";
const APPLICANTS = "신청자";
const USERS = "users";

/** 세션의 신청자 전원 중, 알림을 켜둔 사용자의 FCM 토큰만 모아서 반환 */
async function getApplicantTokens(sessionId: string): Promise<string[]> {
  const applicantsSnap = await db.collection(SESSIONS).doc(sessionId).collection(APPLICANTS).get();
  return collectTokens(applicantsSnap.docs.map((d) => d.id));
}

async function collectTokens(userIds: string[]): Promise<string[]> {
  const tokens: string[] = [];
  for (const uid of userIds) {
    const userDoc = await db.collection(USERS).doc(uid).get();
    if (!userDoc.exists) continue;
    if (userDoc.get("notificationsEnabled") === false) continue; // 사용자가 알림을 꺼둔 경우 제외
    const token = userDoc.get("fcmToken");
    if (typeof token === "string" && token.length > 0) tokens.push(token);
  }
  return tokens;
}

async function sendToTokens(tokens: string[], title: string, body: string) {
  if (tokens.length === 0) return;
  await messaging.sendEachForMulticast({
    tokens,
    notification: { title, body },
  });
}

// ─────────────────────────────────────────────────────
// 1. 상태 변경(모집완료/경기확정/취소됨) 또는 일정 변경 시 즉시 알림
// ─────────────────────────────────────────────────────
export const onSessionUpdated = onDocumentUpdated(`${SESSIONS}/{sessionId}`, async (event) => {
  const before = event.data?.before.data();
  const after = event.data?.after.data();
  if (!before || !after) return;

  const sessionId = event.params.sessionId;
  const statusChanged = before["상태"] !== after["상태"];
  const scheduleChanged =
    before["시작시간"] !== after["시작시간"] ||
    before["종료시간"] !== after["종료시간"] ||
    before["지역"] !== after["지역"];

  if (!statusChanged && !scheduleChanged) return;

  let body: string | null = null;
  if (statusChanged && after["상태"] === "경기확정") body = "경기가 확정되었습니다.";
  else if (statusChanged && after["상태"] === "모집완료") body = "모집 인원이 모두 채워졌습니다.";
  else if (statusChanged && after["상태"] === "취소됨") body = "인원 부족으로 모집이 취소되었습니다.";
  else if (scheduleChanged) body = "일정 또는 장소가 변경되었습니다.";

  if (!body) return;

  const tokens = await getApplicantTokens(sessionId);
  await sendToTokens(tokens, "BeepBeep 예약 알림", body);
  logger.info(`onSessionUpdated ${sessionId}: ${body} (대상 ${tokens.length}명)`);
});

// ─────────────────────────────────────────────────────
// 2. 매일 1회: 마감 임박 인원부족 리마인드 + 날짜 도래 시 자동 취소
// ─────────────────────────────────────────────────────
export const dailyRecruitmentCheck = onSchedule("every 24 hours", async () => {
  const now = Date.now();
  const ONE_DAY_MS = 24 * 60 * 60 * 1000;

  const snap = await db.collection(SESSIONS).where("상태", "==", "모집중").get();

  for (const doc of snap.docs) {
    const data = doc.data();
    const start = data["시작시간"] as number | undefined;
    if (!start) continue;

    const creatorId = data["생성자ID"] as string | undefined;
    const applicantTokens = await getApplicantTokens(doc.id);
    const creatorTokens = creatorId ? await collectTokens([creatorId]) : [];
    const tokens = Array.from(new Set([...applicantTokens, ...creatorTokens]));

    if (start <= now) {
      // 세션 날짜가 지났는데 아직 모집중 → 자동 취소
      await doc.ref.update({ "상태": "취소됨" });
      await sendToTokens(tokens, "BeepBeep 예약 알림", "인원 부족으로 모집이 자동 취소되었습니다.");
      logger.info(`dailyRecruitmentCheck: ${doc.id} 자동 취소`);
    } else if (start - now <= ONE_DAY_MS) {
      // 마감 하루 이내인데 아직 모집중 → 리마인드
      await sendToTokens(tokens, "BeepBeep 예약 알림", "훈련 일정이 임박했는데 아직 인원이 부족합니다.");
      logger.info(`dailyRecruitmentCheck: ${doc.id} 인원부족 리마인드`);
    }
  }
});

// ─────────────────────────────────────────────────────
// 3. 매시간: 경기확정 세션의 시작 1시간 전 알림 (중복 발송 방지)
// ─────────────────────────────────────────────────────
export const upcomingSessionReminder = onSchedule("every 60 minutes", async () => {
  const now = Date.now();
  const ONE_HOUR_MS = 60 * 60 * 1000;

  const snap = await db
    .collection(SESSIONS)
    .where("상태", "==", "경기확정")
    .where("시작알림전송여부", "==", false)
    .get();

  for (const doc of snap.docs) {
    const data = doc.data();
    const start = data["시작시간"] as number | undefined;
    if (!start) continue;
    if (start - now <= ONE_HOUR_MS && start - now > 0) {
      const tokens = await getApplicantTokens(doc.id);
      await sendToTokens(tokens, "BeepBeep 예약 알림", "곧 경기가 시작됩니다.");
      await doc.ref.update({ "시작알림전송여부": true });
      logger.info(`upcomingSessionReminder: ${doc.id} 경기 시작 임박 알림 발송`);
    }
  }
});
