use jni::objects::{JByteArray, JString, JValue};
use jni::sys::jsize;
use jni::JNIEnv;

/// Convert a Java String to a Rust String.
pub fn jstring_to_string(env: &mut JNIEnv, jstr: &JString) -> String {
    if jstr.is_null() {
        return String::new();
    }
    match env.get_string(jstr) {
        Ok(java_str) => java_str.into(),
        Err(_) => String::new(),
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
