/*
 * Copyright (C) 2017-2020 Jakob Nixdorf
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.shadowice.flocke.andotp.Activities;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import androidx.appcompat.widget.Toolbar;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewStub;
import android.view.WindowManager.LayoutParams;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.shadowice.flocke.andotp.R;
import org.shadowice.flocke.andotp.Utilities.Constants;
import org.shadowice.flocke.andotp.Utilities.EncryptionHelper;
import org.shadowice.flocke.andotp.Utilities.EncryptionHelper.PBKDF2Credentials;

import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;

import static org.shadowice.flocke.andotp.Utilities.Constants.AuthMethod;

public class AuthenticateActivity extends ThemedActivity
    implements EditText.OnEditorActionListener, View.OnClickListener {

    private AuthMethod authMethod;
    private String newEncryption = "";
    private String existingAuthCredentials;
    private boolean isAuthUpgrade = false;

    private TextInputEditText passwordInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (! settings.getScreenshotsEnabled())
            getWindow().setFlags(LayoutParams.FLAG_SECURE, LayoutParams.FLAG_SECURE);

        authMethod = settings.getAuthMethod();
        newEncryption = getIntent().getStringExtra(Constants.EXTRA_AUTH_NEW_ENCRYPTION);
        existingAuthCredentials = settings.getAuthCredentials();
        if (existingAuthCredentials.isEmpty()) {
            existingAuthCredentials = settings.getOldCredentials(authMethod);
            isAuthUpgrade = true;
        }

        // If our password is still empty at this point, we can't do anything.
        if (existingAuthCredentials.isEmpty()) {
            int missingPwResId = (authMethod == AuthMethod.PASSWORD)
                    ? R.string.auth_toast_password_missing : R.string.auth_toast_pin_missing;
            Toast.makeText(this, missingPwResId, Toast.LENGTH_LONG).show();
            finishWithResult(true, null);
        }
        // If we're not using password or pin for auth method, we have nothing to authenticate here.
        if (authMethod != AuthMethod.PASSWORD && authMethod != AuthMethod.PIN) {
            finishWithResult(true, null);
        }

        setTitle(R.string.auth_activity_title);
        setContentView(R.layout.activity_container);
        initToolbar();
        initPasswordViews();

        getWindow().setSoftInputMode(LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }

    private void initToolbar() {
        Toolbar toolbar = findViewById(R.id.container_toolbar);
        toolbar.setNavigationIcon(null);
        setSupportActionBar(toolbar);
    }

    private void initPasswordViews() {
        ViewStub stub = findViewById(R.id.container_stub);
        stub.setLayoutResource(R.layout.content_authenticate);
        View v = stub.inflate();

        initPasswordLabelView(v);
        initPasswordLayoutView(v);
        initPasswordInputView(v);
        initUnlockButtonView(v);
    }

    private void initPasswordLabelView(View v) {
        int labelMsg = getIntent().getIntExtra(Constants.EXTRA_AUTH_MESSAGE, R.string.auth_msg_authenticate);
        TextView passwordLabel = v.findViewById(R.id.passwordLabel);
        passwordLabel.setText(labelMsg);
    }

    private void initPasswordLayoutView(View v) {
        TextInputLayout passwordLayout = v.findViewById(R.id.passwordLayout);

        int hintResId = (authMethod == AuthMethod.PASSWORD) ? R.string.auth_hint_password : R.string.auth_hint_pin;
        passwordLayout.setHint(getString(hintResId));

        if (settings.getBlockAccessibility())
            passwordLayout.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && settings.getBlockAutofill())
            passwordLayout.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
    }

    private void initPasswordInputView(View v) {
        passwordInput = v.findViewById(R.id.passwordEdit);
        int inputType = (authMethod == AuthMethod.PASSWORD)
                ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)
                : (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        passwordInput.setInputType(inputType);
        passwordInput.setTransformationMethod(new PasswordTransformationMethod());
        passwordInput.setOnEditorActionListener(this);
    }

    private void initUnlockButtonView(View v) {
        Button unlockButton = v.findViewById(R.id.buttonUnlock);
        unlockButton.setOnClickListener(this);
    }

    @Override
    public void onClick(View view) {
        checkPassword(passwordInput.getText().toString());
    }

    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        if (actionId == EditorInfo.IME_ACTION_DONE) {
            checkPassword(v.getText().toString());
            return true;
        }
        return false;
    }

    public void checkPassword(String plainPassword) {
        if (!isAuthUpgrade) {
            try {
                PBKDF2Credentials credentials = EncryptionHelper.generatePBKDF2Credentials(plainPassword, settings.getSalt(), settings.getIterations());
                byte[] passwordArray = Base64.decode(existingAuthCredentials, Base64.URL_SAFE);

                if (Arrays.equals(passwordArray, credentials.password)) {
                    finishWithResult(true, credentials.key);
                } else {
                    finishWithResult(false, null);
                }
            } catch (NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException e) {
                e.printStackTrace();
                finishWithResult(false, null);
            }
        } else {
            String hashedPassword = new String(Hex.encodeHex(DigestUtils.sha256(plainPassword)));

            if (hashedPassword.equals(existingAuthCredentials)) {
                byte[] key = settings.setAuthCredentials(plainPassword);

                if (key == null)
                    Toast.makeText(this, R.string.settings_toast_auth_upgrade_failed, Toast.LENGTH_LONG).show();

                if (authMethod == AuthMethod.PASSWORD)
                    settings.removeAuthPasswordHash();
                else if (authMethod == AuthMethod.PIN)
                    settings.removeAuthPINHash();

                finishWithResult(true, key);
            } else {
                finishWithResult(false, null);
            }
        }
    }

    // End with a result
    public void finishWithResult(boolean success, byte[] key) {
        Intent data = new Intent();

        if (newEncryption != null && ! newEncryption.isEmpty())
            data.putExtra(Constants.EXTRA_AUTH_NEW_ENCRYPTION, newEncryption);
        if (key != null)
            data.putExtra(Constants.EXTRA_AUTH_PASSWORD_KEY, key);
        if (success)
            setResult(RESULT_OK, data);

        finish();
    }

    // Go back to the main activity
    @Override
    public void onBackPressed() {
        finishWithResult(false, null);
        super.onBackPressed();
    }
}
