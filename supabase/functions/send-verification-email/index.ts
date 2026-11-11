// Supabase Edge Function: send-verification-email
//
// Автоматично надсилає лист із 6-значним кодом на Gmail користувача.
// Розгортання:
//   supabase functions deploy send-verification-email
//   supabase secrets set RESEND_API_KEY=re_xxxxxxxx
//
// Рекомендований шаблон у Resend: відправник "Verstappen Race Dash
// <security@ваш-домен>" (довільну Gmail-адресу Resend не прийме, тому
// для тесту можна використати onboarding@resend.dev).
//
// Запит:  POST /functions/v1/send-verification-email
//         headers: Authorization: Bearer <SUPABASE_ANON_KEY>, apikey: <SUPABASE_ANON_KEY>
//         body:    { "to": "...@gmail.com", "code": "123456", "purpose": "registration" | "login" }
// Відповідь: { "sent": true } або { "sent": false, "error": "..." }

const RESEND_ENDPOINT = "https://api.resend.com/emails";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
};

function buildSubject(purpose: string): string {
  return purpose === "registration"
    ? "Verstappen Race Dash — код реєстрації"
    : "Verstappen Race Dash — код входу (2FA)";
}

function buildHtml(code: string, purpose: string): string {
  const title = purpose === "registration" ? "ПІДТВЕРДЖЕННЯ РЕЄСТРАЦІЇ" : "ДВОФАКТОРНИЙ ВХІД";
  return `<!doctype html><html><body style="margin:0;padding:0;background:#050913;font-family:Arial,sans-serif;">
  <div style="max-width:480px;margin:0 auto;padding:28px 18px;color:#f4f7fc;">
    <div style="display:flex;align-items:center;gap:10px;border-bottom:1px solid rgba(115,160,207,.2);padding-bottom:16px;">
      <div style="width:32px;height:30px;display:flex;align-items:center;justify-content:center;font-weight:bold;font-style:italic;color:#06101c;transform:skewX(-12deg);background:linear-gradient(135deg,#f8d55d 22%,#fb4054 23%,#fb4054 69%,#176fb7 70%);">V</div>
      <div>
        <div style="font-size:10px;font-weight:bold;letter-spacing:.16em;">VERSTAPPEN RACE DASH</div>
        <div style="color:#536882;font-size:8px;font-weight:bold;letter-spacing:.2em;margin-top:3px;">SECURITY CORE</div>
      </div>
    </div>
    <div style="margin-top:18px;padding:18px 16px;border:1px solid rgba(115,160,207,.17);border-radius:14px;background:rgba(11,19,33,.92);">
      <div style="color:#f2c94c;font-size:13px;font-weight:bold;letter-spacing:.08em;">${title}</div>
      <p style="color:#8a9db7;font-size:12px;line-height:1.6;">Ваш код підтвердження:</p>
      <div style="text-align:center;padding:14px;border:1px solid rgba(71,165,255,.35);border-radius:10px;background:rgba(5,10,19,.72);font-size:30px;font-weight:bold;letter-spacing:.4em;color:#47a5ff;">${code}</div>
      <p style="color:#536882;font-size:11px;">Код дійсний 5 хвилин. Якщо це були не ви — просто проігноруйте лист.</p>
    </div>
    <div style="margin-top:16px;text-align:center;color:#536882;font-size:8px;font-weight:bold;letter-spacing:.18em;">MAX VERSTAPPEN · RACE DASH · SECURITY</div>
  </div></body></html>`;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  if (req.method !== "POST") {
    return new Response(JSON.stringify({ sent: false, error: "POST only" }), {
      status: 405,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }

  const apiKey = Deno.env.get("RESEND_API_KEY");
  if (!apiKey) {
    return new Response(
      JSON.stringify({ sent: false, error: "RESEND_API_KEY not configured" }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } },
    );
  }

  let payload: { to?: string; code?: string; purpose?: string };
  try {
    payload = await req.json();
  } catch {
    return new Response(JSON.stringify({ sent: false, error: "Bad JSON" }), {
      status: 400,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }

  const to = (payload.to ?? "").trim();
  const code = (payload.code ?? "").trim();
  const purpose = payload.purpose === "login" ? "login" : "registration";

  if (!/^[A-Za-z0-9._%+-]+@gmail\.com$/.test(to) || !/^\d{6}$/.test(code)) {
    return new Response(JSON.stringify({ sent: false, error: "Invalid input" }), {
      status: 422,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }

  const resendResponse = await fetch(RESEND_ENDPOINT, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${apiKey}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      from: Deno.env.get("MAIL_FROM") ?? "Verstappen Race Dash <onboarding@resend.dev>",
      to: [to],
      subject: buildSubject(purpose),
      html: buildHtml(code, purpose),
      text: `Код: ${code} (дійсний 5 хвилин)`,
    }),
  });

  if (!resendResponse.ok) {
    const detail = await resendResponse.text();
    return new Response(JSON.stringify({ sent: false, error: detail }), {
      status: 502,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }

  return new Response(JSON.stringify({ sent: true }), {
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
});
