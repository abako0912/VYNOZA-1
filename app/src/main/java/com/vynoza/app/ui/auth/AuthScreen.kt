package com.vynoza.app.ui.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vynoza.app.R
import com.vynoza.app.ui.components.VynozaWordmark
import com.vynoza.app.ui.theme.VynozaBorderSubtle
import com.vynoza.app.ui.theme.VynozaLavenderSoft
import com.vynoza.app.ui.theme.VynozaObsidian
import com.vynoza.app.ui.theme.VynozaPurplePrimary
import com.vynoza.app.ui.theme.VynozaSuccess
import com.vynoza.app.ui.theme.VynozaSurfaceDeep
import com.vynoza.app.ui.theme.VynozaSurfaceElevated

@Composable
fun AuthScreen(
  viewModel: AuthViewModel,
  onAuthSuccess: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  var passwordVisible by remember { mutableStateOf(false) }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(VynozaObsidian)
  ) {
    Image(
      painter = painterResource(id = R.drawable.img_vynoza_auth_hero_1795187791355),
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = Modifier
        .fillMaxWidth()
        .height(340.dp),
    )
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(360.dp)
        .background(
          Brush.verticalGradient(
            colors = listOf(
              VynozaObsidian.copy(alpha = 0.25f),
              VynozaObsidian.copy(alpha = 0.78f),
              VynozaObsidian,
            )
          )
        )
    )

    Column(
      modifier = Modifier
        .fillMaxSize()
        .statusBarsPadding()
        .navigationBarsPadding()
        .imePadding()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
        horizontalAlignment = Alignment.Start,
      ) {
        VynozaWordmark()
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = stringResource(R.string.brand_tagline),
          style = MaterialTheme.typography.labelLarge,
          color = VynozaLavenderSoft,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
          text = stringResource(R.string.auth_headline),
          style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
          color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = stringResource(R.string.auth_subtitle),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      // Auth Card
      Surface(
        modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
        shape = RoundedCornerShape(24.dp),
        color = VynozaSurfaceElevated.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, VynozaBorderSubtle),
      ) {
        Column(
          modifier = Modifier.padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          if (uiState.mode != AuthMode.RESET_PASSWORD) {
            Surface(
              shape = RoundedCornerShape(14.dp),
              color = VynozaSurfaceDeep,
              border = BorderStroke(1.dp, VynozaBorderSubtle),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                AuthModeTab(
                  label = stringResource(R.string.auth_sign_in_cta),
                  selected = uiState.mode == AuthMode.SIGN_IN,
                  onClick = { viewModel.setMode(AuthMode.SIGN_IN) },
                  modifier = Modifier.weight(1f).testTag("auth_mode_signin_tab"),
                )
                AuthModeTab(
                  label = stringResource(R.string.auth_create_account_cta),
                  selected = uiState.mode == AuthMode.CREATE_ACCOUNT,
                  onClick = { viewModel.setMode(AuthMode.CREATE_ACCOUNT) },
                  modifier = Modifier.weight(1f).testTag("auth_mode_create_tab"),
                )
              }
            }
          } else {
            Text(
              text = "Reset Password",
              style = MaterialTheme.typography.titleLarge,
              color = MaterialTheme.colorScheme.onSurface,
            )
          }

          if (uiState.mode == AuthMode.CREATE_ACCOUNT) {
            OutlinedTextField(
              value = uiState.displayName,
              onValueChange = viewModel::onDisplayNameChanged,
              label = { Text("Display Name") },
              singleLine = true,
              leadingIcon = {
                Icon(Icons.Rounded.Person, contentDescription = null, tint = VynozaLavenderSoft)
              },
              keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
              shape = RoundedCornerShape(14.dp),
              colors = vynozaFieldColors(),
              modifier = Modifier.fillMaxWidth().testTag("auth_display_name_input"),
            )
          }

          OutlinedTextField(
            value = uiState.email,
            onValueChange = viewModel::onEmailChanged,
            label = { Text("Email") },
            singleLine = true,
            leadingIcon = {
              Icon(Icons.Rounded.Email, contentDescription = null, tint = VynozaLavenderSoft)
            },
            keyboardOptions =
              KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction =
                  if (uiState.mode == AuthMode.RESET_PASSWORD) ImeAction.Done else ImeAction.Next,
              ),
            shape = RoundedCornerShape(14.dp),
            colors = vynozaFieldColors(),
            modifier = Modifier.fillMaxWidth().testTag("auth_email_input"),
          )

          if (uiState.mode != AuthMode.RESET_PASSWORD) {
            OutlinedTextField(
              value = uiState.password,
              onValueChange = viewModel::onPasswordChanged,
              label = { Text("Password") },
              singleLine = true,
              leadingIcon = {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = VynozaLavenderSoft)
              },
              trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                  Icon(
                    imageVector =
                      if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (passwordVisible) "Hide password" else "Show password",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              },
              visualTransformation =
                if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
              keyboardOptions =
                KeyboardOptions(
                  keyboardType = KeyboardType.Password,
                  imeAction =
                    if (uiState.mode == AuthMode.CREATE_ACCOUNT) ImeAction.Next else ImeAction.Done,
                ),
              shape = RoundedCornerShape(14.dp),
              colors = vynozaFieldColors(),
              modifier = Modifier.fillMaxWidth().testTag("auth_password_input"),
            )
          }

          if (uiState.mode == AuthMode.CREATE_ACCOUNT) {
            OutlinedTextField(
              value = uiState.confirmPassword,
              onValueChange = viewModel::onConfirmPasswordChanged,
              label = { Text("Confirm Password") },
              singleLine = true,
              leadingIcon = {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = VynozaLavenderSoft)
              },
              visualTransformation =
                if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
              keyboardOptions =
                KeyboardOptions(
                  keyboardType = KeyboardType.Password,
                  imeAction = ImeAction.Done,
                ),
              shape = RoundedCornerShape(14.dp),
              colors = vynozaFieldColors(),
              modifier = Modifier.fillMaxWidth().testTag("auth_confirm_password_input"),
            )
          }

          if (uiState.errorMessage != null) {
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
            ) {
              Text(
                text = uiState.errorMessage!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(12.dp),
              )
            }
          }

          if (uiState.infoMessage != null) {
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = VynozaSuccess.copy(alpha = 0.14f),
              border = BorderStroke(1.dp, VynozaSuccess.copy(alpha = 0.5f)),
            ) {
              Text(
                text = uiState.infoMessage!!,
                style = MaterialTheme.typography.bodyMedium,
                color = VynozaSuccess,
                modifier = Modifier.padding(12.dp),
              )
            }
          }

          Button(
            onClick = { viewModel.submit(onAuthSuccess) },
            enabled = !uiState.isLoading,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
              containerColor = VynozaPurplePrimary,
              contentColor = Color.White,
            ),
            modifier = Modifier
              .fillMaxWidth()
              .height(52.dp)
              .testTag("auth_submit_button"),
          ) {
            if (uiState.isLoading) {
              CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = Color.White,
                strokeWidth = 2.5.dp,
              )
            } else {
              val ctaLabel =
                when (uiState.mode) {
                  AuthMode.SIGN_IN -> stringResource(R.string.auth_sign_in_cta)
                  AuthMode.CREATE_ACCOUNT -> stringResource(R.string.auth_create_account_cta)
                  AuthMode.RESET_PASSWORD -> stringResource(R.string.auth_reset_password_cta)
                }
              Text(text = ctaLabel, style = MaterialTheme.typography.titleMedium)
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
          ) {
            if (uiState.mode == AuthMode.RESET_PASSWORD) {
              TextButton(onClick = { viewModel.setMode(AuthMode.SIGN_IN) }) {
                Text(stringResource(R.string.auth_back_to_sign_in), color = VynozaLavenderSoft)
              }
            } else if (uiState.mode == AuthMode.SIGN_IN) {
              TextButton(
                onClick = { viewModel.setMode(AuthMode.RESET_PASSWORD) },
                modifier = Modifier.testTag("auth_forgot_password_button"),
              ) {
                Text(stringResource(R.string.auth_forgot_password), color = VynozaLavenderSoft)
              }
            }
          }
        }
      }

      Surface(
        modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
        shape = RoundedCornerShape(16.dp),
        color = VynozaSurfaceElevated.copy(alpha = 0.78f),
        border = BorderStroke(1.dp, VynozaBorderSubtle),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(14.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Box(
            modifier = Modifier
              .size(32.dp)
              .clip(RoundedCornerShape(10.dp))
              .background(VynozaPurplePrimary.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.Rounded.Lock,
              contentDescription = null,
              tint = VynozaLavenderSoft,
              modifier = Modifier.size(18.dp),
            )
          }
          Text(
            text = stringResource(R.string.auth_security_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

@Composable
private fun AuthModeTab(
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(10.dp))
      .background(if (selected) VynozaPurplePrimary else Color.Transparent)
      .clickable(onClick = onClick)
      .minimumInteractiveComponentSize()
      .padding(vertical = 10.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelLarge,
      color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun vynozaFieldColors() =
  OutlinedTextFieldDefaults.colors(
    focusedContainerColor = VynozaSurfaceDeep,
    unfocusedContainerColor = VynozaSurfaceDeep,
    focusedBorderColor = VynozaPurplePrimary,
    unfocusedBorderColor = VynozaBorderSubtle,
  )
