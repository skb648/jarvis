use jni::objects::{JByteArray, JString, JValue};
use jni::sys::jsize;
use jni::JNIEnv;

/// Convert a Java String to a Rust String.
/// CRITICAL FIX (v7): Added diagnostic logging for key length verification.
/// If a standard Gemini key is 39 chars and Rust sees 40, the JNI bridge
/// is injecting garbage. This logging helps detect that immediately.
pub fn jstring_to_string(env: &mut JNIEnv, jstr: &JString) -> String {
    if jstr.is_null() {
        log::warn!("jstring_to_string: received null JString — returning empty string");
        return String::new();
    }
    match env.get_string(jstr) {
        Ok(java_str) => {
            let rust_string: String = java_str.into();
            // Diagnostic: log the exact byte length and char length to detect
            // null terminators or garbage trailing bytes from JNI conversion
            log::info!(
                "JNI string conversion — bytes={}, chars={}, first_4='{}'",
                rust_string.len(),
                rust_string.chars().count(),
                if rust_string.len() >= 4 { &rust_string[..4] } else { &rust_string }
            );
            // Strip any trailing null bytes that might leak from JNI
            let trimmed = rust_string.trim_end_matches('\0').to_string();
            if trimmed.len() != rust_string.len() {
                log::warn!(
                    "JNI string had {} trailing null bytes — stripped! Original len={}, Clean len={}",
                    rust_string.len() - trimmed.len(),
                    rust_string.len(),
                    trimmed.len()
                );
            }
            trimmed
        }
        Err(e) => {
            log::error!("jstring_to_string: env.get_string() failed: {:?}", e);
            String::new()
        }
    }
}

/// Convert a Rust String to a Java String.
pub fn string_to_jstring<'local>(env: &mut JNIEnv<'local>, s: &str) -> JString<'local> {
    env.new_string(s)
        .unwrap_or_else(|_| env.new_string("").unwrap())
}

/// Convert a Java byte array to a Rust Vec<u8>.
pub fn jbytearray_to_vec(env: &mut JNIEnv, array: &JByteArray) -> Vec<u8> {
    if array.is_null() {
        return Vec::new();
    }

    let len = env.get_array_length(array).unwrap_or(0);
    if len <= 0 {
        return Vec::new();
    }

    let mut buf = vec![0i8; len as usize];
    match env.get_byte_array_region(array, 0, &mut buf) {
        Ok(()) => buf.iter().map(|&b| b as u8).collect(),
        Err(_) => Vec::new(),
    }
}

/// Convert a Rust Vec<u8> to a Java byte array.
#[allow(dead_code)]
pub fn vec_to_jbytearray<'local>(env: &mut JNIEnv<'local>, data: &[u8]) -> JByteArray<'local> {
    let len = data.len() as jsize;
    match env.new_byte_array(len) {
        Ok(array) => {
            let signed: Vec<i8> = data.iter().map(|&b| b as i8).collect();
            let _ = env.set_byte_array_region(&array, 0, &signed);
            array
        }
        Err(_) => env.new_byte_array(0).unwrap(),
    }
}

#[allow(dead_code)]
pub fn call_kotlin_static_void(
    env: &mut JNIEnv,
    class_path: &str,
    method_name: &str,
    signature: &str,
    args: &[JValue],
) -> Result<(), String> {
    let class = env
        .find_class(class_path)
        .map_err(|e| format!("Class not found: {}", e))?;

    env.call_static_method(&class, method_name, signature, args)
        .map_err(|e| format!("Method call failed: {}", e))?;

    Ok(())
}
