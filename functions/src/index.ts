import { initializeApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { onDocumentUpdated } from "firebase-functions/v2/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { onRequest } from "firebase-functions/v2/https";
import { logger } from "firebase-functions/v2";
import { CloudTasksClient } from "@google-cloud/tasks";

initializeApp();
const db = getFirestore();
const messaging = getMessaging();
const tasksClient = new CloudTasksClient();

const SESSIONS = "match_sessions";
const APPLICANTS = "신청자";
const USERS = "users";
const PROJECT_ID = "beep-beep-a187d";
const LOCATION = "asia-northeast3";
const QUEUE = "match-reminder-2";
const ONE_HOUR_MS = 60 * 60 * 1000;
const ONE_DAY_MS = 24 * 60 * 60 * 1000;

async function getApplicantTokens(sessionId: string): Promise<string[]> {
  const applicantsSnap = await db.collection(SESSIONS).doc(sessionId).collection(APPLICANTS).get();
  return collectTokens(applicantsSnap.docs.map((d) => d.id));
}

async function collectTokens(userIds: string[]): Promise<string[]> {
  const tokens: string[] = [];
  for (const uid of userIds) {
    const userDoc = await db.collection(USERS).doc(uid).get();
    if (!userDoc.exists) continue;
    if (userDoc.get("notificationsEnabled") === false) continue;
    const token = userDoc.get("fcmToken");
    if (typeof token === "string" && token.length > 0) tokens.push(token);
  }
  return tokens;
}

async function sendToTokens(tokens: string[], title: string, body: string) {
  if (tokens.length === 0) return;
  await messaging.sendEachForMulticast({ tokens, notification: { title, body } });
}

async function scheduleReminder(sessionId: string, sendAtMs: number) {
  const parent = tasksClient.queuePath(PROJECT_ID, LOCATION, QUEUE);
  const url = `https://${LOCATION}-${PROJECT_ID}.cloudfunctions.net/sendMatchReminder`;

  await tasksClient.createTask({
    parent,
    task: {
      httpRequest: {
        httpMethod: "POST" as const,
        url,
        headers: { "Content-Type": "application/json" },
        body: Buffer.from(JSON.stringify({ sessionId })).toString("base64"),
      },
      scheduleTime: {
        seconds: Math.floor(sendAtMs / 1000),
      },
    },
  });
}

// ─────────────────────────────────────────────────────
// 1. 상태 변경(모집완료/경기확정/취소됨) 또는 일정 변경 시 즉시 알림
//    + 경기확정 시 시작 1시간 전 Cloud Task 예약
// ─────────────────────────────────────────────────────
export const onSessionUpdated = onDocumentUpdated(`${SESSIONS}/{sessionId}`, async (event) => {
  const before = event.data?.before.data();
  const after  = event.data?.after.data();
  if (!before || !after) return;

  const sessionId = event.params.sessionId;
  const statusChanged = before["상태"] !== after["상태"];
  const toSeconds = (v: unknown): number | undefined => {
    if (v instanceof Timestamp) return v.seconds;
    if (typeof v === "number") return Math.floor(v / 1000);
    return undefined;
  };
  const scheduleChanged =
    toSeconds(before["시작시간"]) !== toSeconds(after["시작시간"]) ||
    toSeconds(before["종료시간"]) !== toSeconds(after["종료시간"]) ||
    before["지역"] !== after["지역"];

  if (!statusChanged && !scheduleChanged) return;

  let body: string | null = null;
  if (statusChanged && after["상태"] === "경기확정") body = "경기가 확정되었습니다.";
  else if (statusChanged && after["상태"] === "모집완료") body = "모집 인원이 모두 채워졌습니다.";
  else if (statusChanged && after["상태"] === "취소됨")  body = "인원 부족으로 모집이 취소되었습니다.";
  else if (scheduleChanged) body = "일정 또는 장소가 변경되었습니다.";

  if (body) {
    const tokens = await getApplicantTokens(sessionId);
    await sendToTokens(tokens, "BeepBeep 예약 알림", body);
    logger.info(`onSessionUpdated ${sessionId}: ${body} (대상 ${tokens.length}명)`);
  }

  // 경기확정 시 시작 1시간 전 알림 Cloud Task 예약
  if (statusChanged && after["상태"] === "경기확정") {
    const startTs = after["시작시간"] as Timestamp | undefined;
    if (startTs) {
      const reminderMs = startTs.toMillis() - ONE_HOUR_MS;
      if (reminderMs > Date.now()) {
        await scheduleReminder(sessionId, reminderMs);
        logger.info(`onSessionUpdated ${sessionId}: 1시간 전 알림 예약 → ${new Date(reminderMs).toISOString()}`);
      } else {
        logger.info(`onSessionUpdated ${sessionId}: 시작 1시간 이내라 즉시 발송`);
        const tokens = await getApplicantTokens(sessionId);
        await sendToTokens(tokens, "BeepBeep 예약 알림", "곧 경기가 시작됩니다.");
        await db.collection(SESSIONS).doc(sessionId).update({ "시작알림전송여부": true });
      }
    }
  }
});

// ─────────────────────────────────────────────────────
// 2. 매일 1회: 마감 임박 인원부족 리마인드 + 날짜 도래 시 자동 취소
// ─────────────────────────────────────────────────────
export const dailyRecruitmentCheck = onSchedule("every 24 hours", async () => {
  const now = Date.now();

  const snap = await db.collection(SESSIONS).where("상태", "==", "모집중").get();

  for (const doc of snap.docs) {
    const data = doc.data();
    const startTs = data["시작시간"] as Timestamp | undefined;
    if (!startTs) continue;
    const start = startTs.toMillis();

    const creatorId = data["생성자ID"] as string | undefined;
    const applicantTokens = await getApplicantTokens(doc.id);
    const creatorTokens   = creatorId ? await collectTokens([creatorId]) : [];
    const tokens = Array.from(new Set([...applicantTokens, ...creatorTokens]));

    if (start <= now) {
      await doc.ref.update({ "상태": "취소됨" });
      await sendToTokens(tokens, "BeepBeep 예약 알림", "인원 부족으로 모집이 자동 취소되었습니다.");
      logger.info(`dailyRecruitmentCheck: ${doc.id} 자동 취소`);
    } else if (start - now <= ONE_DAY_MS) {
      await sendToTokens(tokens, "BeepBeep 예약 알림", "훈련 일정이 임박했는데 아직 인원이 부족합니다.");
      logger.info(`dailyRecruitmentCheck: ${doc.id} 인원부족 리마인드`);
    }
  }
});

// ─────────────────────────────────────────────────────
// 3. Cloud Task 핸들러: 경기 시작 1시간 전 FCM 발송
// ─────────────────────────────────────────────────────
export const sendMatchReminder = onRequest({ region: LOCATION }, async (req, res) => {
  const sessionId = req.body.sessionId as string | undefined;
  if (!sessionId) {
    res.status(400).send("sessionId required");
    return;
  }

  const docSnap = await db.collection(SESSIONS).doc(sessionId).get();
  if (!docSnap.exists) {
    res.status(404).send("session not found");
    return;
  }

  const data = docSnap.data()!;
  if (data["상태"] !== "경기확정") {
    res.status(200).send("session not confirmed, skip");
    return;
  }
  if (data["시작알림전송여부"] === true) {
    res.status(200).send("already sent");
    return;
  }

  const tokens = await getApplicantTokens(sessionId);
  await sendToTokens(tokens, "BeepBeep 예약 알림", "곧 경기가 시작됩니다.");
  await docSnap.ref.update({ "시작알림전송여부": true });
  logger.info(`sendMatchReminder: ${sessionId} 알림 발송 (${tokens.length}명)`);
  res.status(200).send("ok");
});
