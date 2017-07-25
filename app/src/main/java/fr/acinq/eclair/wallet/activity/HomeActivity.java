package fr.acinq.eclair.wallet.activity;

import android.annotation.SuppressLint;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentStatePagerAdapter;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.ViewPager;
import android.support.v7.app.ActionBar;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.TextView;
import android.widget.Toast;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

import akka.dispatch.OnComplete;
import fr.acinq.bitcoin.MilliSatoshi;
import fr.acinq.bitcoin.package$;
import fr.acinq.eclair.wallet.EclairEventService;
import fr.acinq.eclair.wallet.R;
import fr.acinq.eclair.wallet.customviews.CoinAmountView;
import fr.acinq.eclair.wallet.events.BitcoinPaymentEvent;
import fr.acinq.eclair.wallet.events.ChannelUpdateEvent;
import fr.acinq.eclair.wallet.events.LNBalanceUpdateEvent;
import fr.acinq.eclair.wallet.events.LNNewChannelFailureEvent;
import fr.acinq.eclair.wallet.events.LNNewChannelOpenedEvent;
import fr.acinq.eclair.wallet.events.LNPaymentEvent;
import fr.acinq.eclair.wallet.events.LNPaymentFailedEvent;
import fr.acinq.eclair.wallet.events.WalletBalanceUpdateEvent;
import fr.acinq.eclair.wallet.fragments.ChannelsListFragment;
import fr.acinq.eclair.wallet.fragments.PaymentsListFragment;
import fr.acinq.eclair.wallet.fragments.ReceivePaymentFragment;
import fr.acinq.eclair.wallet.utils.CoinUtils;
import fr.acinq.eclair.wallet.utils.Validators;
import scala.concurrent.ExecutionContext;

public class HomeActivity extends EclairActivity {

  public static final String EXTRA_PAGE = "fr.acinq.eclair.swordfish.EXTRA_PAGE";
  private static final String TAG = "Home Activity";
  private ViewPager mViewPager;
  private HomePagerAdapter mPagerAdapter;
  private PaymentsListFragment mPaymentsListFragment;
  private ChannelsListFragment mChannelsListFragment;
  private ReceivePaymentFragment mReceivePaymentFragment;

  private ViewGroup mSendButtonsView;
  private ViewGroup mSendButtonsToggleView;
  private FloatingActionButton mSendButton;
  private FloatingActionButton mDisabledSendButton;

  private ViewGroup mOpenChannelsButtonsView;
  private ViewGroup mOpenChannelButtonsToggleView;
  private FloatingActionButton mOpenChannelButton;

  private CoinAmountView mTotalBalanceView;
  private TextView mWalletBalanceView;
  private TextView mLNBalanceView;

  private View mIntroView;
  private View mIntroWelcome;
  private View mIntroReceive;
  private View mIntroOpenChannel;
  private View mIntroOpenChannelPatience;
  private View mIntroSendPayment;

  private int introStep = 0;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setTheme(R.style.AppTheme);
    setContentView(R.layout.activity_home);

    mIntroView = findViewById(R.id.home_intro);
    mIntroView.setVisibility(View.GONE);
    mIntroWelcome = findViewById(R.id.home_intro_welcome);
    mIntroReceive = findViewById(R.id.home_intro_receive);
    mIntroOpenChannel = findViewById(R.id.home_intro_openchannel);
    mIntroOpenChannelPatience = findViewById(R.id.home_intro_openchannel_patience);
    mIntroSendPayment = findViewById(R.id.home_intro_sendpayment);

    Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);
    ActionBar ab = getSupportActionBar();
    ab.setDisplayHomeAsUpEnabled(false);
    ab.setDisplayShowTitleEnabled(false);

    mTotalBalanceView = (CoinAmountView) findViewById(R.id.home_balance_total);
    mWalletBalanceView = (TextView) findViewById(R.id.home_balance_wallet_value);
    mLNBalanceView = (TextView) findViewById(R.id.home_balance_ln_value);

    mSendButtonsView = (ViewGroup) findViewById(R.id.home_send_buttons);
    mSendButtonsToggleView = (ViewGroup) findViewById(R.id.home_send_buttons_toggle);
    mSendButton = (FloatingActionButton) findViewById(R.id.home_send_button);
    mDisabledSendButton = (FloatingActionButton) findViewById(R.id.home_send_button_disabled);

    mOpenChannelsButtonsView = (ViewGroup) findViewById(R.id.home_openchannel_buttons);
    mOpenChannelButtonsToggleView = (ViewGroup) findViewById(R.id.home_openchannel_buttons_toggle);
    mOpenChannelButton = (FloatingActionButton) findViewById(R.id.home_openchannel_button);

    mViewPager = (ViewPager) findViewById(R.id.home_viewpager);
    mViewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
      @Override
      public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
      }

      @Override
      public void onPageSelected(int position) {
        mOpenChannelsButtonsView.setVisibility(position == 2 ? View.VISIBLE : View.GONE);
        mSendButtonsView.setVisibility(position == 1 ? View.VISIBLE : View.GONE);
      }

      @Override
      public void onPageScrollStateChanged(int state) {
      }
    });

    final List<Fragment> fragments = new ArrayList<>();
    mReceivePaymentFragment = new ReceivePaymentFragment();
    fragments.add(mReceivePaymentFragment);
    mPaymentsListFragment = new PaymentsListFragment();
    fragments.add(mPaymentsListFragment);
    mChannelsListFragment = new ChannelsListFragment();
    fragments.add(mChannelsListFragment);
    mPagerAdapter = new HomePagerAdapter(getSupportFragmentManager(), fragments);
    mViewPager.setAdapter(mPagerAdapter);
    if (savedInstanceState != null && savedInstanceState.containsKey("currentPage")) {
      mViewPager.setCurrentItem(savedInstanceState.getInt("currentPage"));
    } else {
      Intent intent = getIntent();
      mViewPager.setCurrentItem(intent.getIntExtra(EXTRA_PAGE, 1));
    }

    (new Thread(new Runnable() {
      @Override
      public void run() {
        SharedPreferences getPrefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        boolean isFirstStart = getPrefs.getBoolean("firstStart", true);
        if (isFirstStart) {
          runOnUiThread(new Runnable() {
            @Override
            public void run() {
              mIntroView.setVisibility(View.VISIBLE);
              mIntroView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                  introStep++;
                  if (introStep > 4) {
                    mIntroView.setVisibility(View.GONE);
                  } else {
                    mIntroView.setVisibility(View.VISIBLE);
                    mIntroWelcome.setVisibility(introStep == 0 ? View.VISIBLE : View.GONE);
                    mIntroReceive.setVisibility(introStep == 1 ? View.VISIBLE : View.GONE);
                    mIntroOpenChannel.setVisibility(introStep == 2 ? View.VISIBLE : View.GONE);
                    mIntroOpenChannelPatience.setVisibility(introStep == 3 ? View.VISIBLE : View.GONE);
                    mIntroSendPayment.setVisibility(introStep == 4 ? View.VISIBLE : View.GONE);
                    if (introStep == 1) {
                      mViewPager.setCurrentItem(0);
                    } else if (introStep == 2 || introStep == 3) {
                      mViewPager.setCurrentItem(2);
                    } else {
                      mViewPager.setCurrentItem(1);
                    }
                  }
                }
              });
            }
          });
          SharedPreferences.Editor e = getPrefs.edit();
          e.putBoolean("firstStart", false);
          e.apply();
        }
      }
    })).start();

    app.fAtCurrentBlockHeight().onComplete(new OnComplete<Object>() {
      @Override
      public void onComplete(Throwable throwable, Object o) throws Throwable {
        runOnUiThread(new Runnable() {
          @Override
          public void run() {
            if (!app.fAtCurrentBlockHeight().isCompleted()) {
              disableSendButton();
            } else {
              enableSendButton();
            }
          }
        });
      }
    }, ExecutionContext.Implicits$.MODULE$.global());
  }

  @Override
  public void onResume() {
    if (!EventBus.getDefault().isRegistered(this)) {
      EventBus.getDefault().register(this);
    }
    super.onResume();
    if (!app.fAtCurrentBlockHeight().isCompleted()) {
      disableSendButton();
    } else {
      enableSendButton();
    }
    app.publishWalletBalance();
    EclairEventService.postLNBalanceEvent();
  }

  @Override
  public void onPause() {
    super.onPause();
    home_closeSendButtons();
    home_closeOpenChannelButtons();
  }

  @Override
  public void onStop() {
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    EventBus.getDefault().unregister(this);
    super.onDestroy();
  }

  @Override
  protected void onSaveInstanceState(Bundle bundle) {
    bundle.putInt("currentPage", mViewPager.getCurrentItem());
    super.onSaveInstanceState(bundle);
  }

  @Override
  protected void onRestoreInstanceState(Bundle savedInstanceState) {
    mViewPager.setCurrentItem(savedInstanceState.getInt("currentPage"));
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.menu_home, menu);
    return super.onCreateOptionsMenu(menu);
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    switch (item.getItemId()) {
      case R.id.menu_home_networkinfos:
        Intent networkInfosIntent = new Intent(this, NetworkInfosActivity.class);
        startActivity(networkInfosIntent);
        return true;
      case R.id.menu_home_about:
        Intent aboutIntent = new Intent(this, AboutActivity.class);
        startActivity(aboutIntent);
        return true;
      default:
        return super.onOptionsItemSelected(item);
    }
  }

  private String readFromClipboard() {
    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
    if (clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemAt(0) != null && clipboard.getPrimaryClip().getItemAt(0).getText() != null) {
      return clipboard.getPrimaryClip().getItemAt(0).getText().toString();
    }
    return "";
  }

  public void home_sendPaste(View view) {
    Intent intent = new Intent(this, CreatePaymentActivity.class);
    intent.putExtra(CreatePaymentActivity.EXTRA_INVOICE, readFromClipboard());
    startActivity(intent);
  }

  public void home_sendScan(View view) {
    Intent intent = new Intent(this, ScanActivity.class);
    intent.putExtra(ScanActivity.EXTRA_SCAN_TYPE, ScanActivity.TYPE_INVOICE);
    startActivity(intent);
  }

  public void home_toggleSendButtons(View view) {
    boolean isVisible = mSendButtonsToggleView.getVisibility() == View.VISIBLE;
    mSendButton.animate().rotation(isVisible ? 0 : -90).setInterpolator(new AccelerateDecelerateInterpolator()).setDuration(150).start();
    mSendButton.setBackgroundTintList(ContextCompat.getColorStateList(this, isVisible ? R.color.colorPrimary : R.color.colorGrey_4));
    mSendButtonsToggleView.setVisibility(isVisible ? View.GONE : View.VISIBLE);
  }

  private void enableSendButton() {
    mDisabledSendButton.setVisibility(View.GONE);
    mSendButton.setVisibility(View.VISIBLE);
  }

  private void disableSendButton() {
    mDisabledSendButton.setVisibility(View.VISIBLE);
    mSendButton.setVisibility(View.GONE);
  }

  public void home_closeSendButtons() {
    mSendButton.animate().rotation(0).setInterpolator(new AccelerateDecelerateInterpolator()).setDuration(150).start();
    mSendButton.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.colorPrimary));
    mSendButtonsToggleView.setVisibility(View.GONE);
  }

  public void home_toggleOpenChannelButtons(View view) {
    boolean isVisible = mOpenChannelButtonsToggleView.getVisibility() == View.VISIBLE;
    mOpenChannelButton.animate().rotation(isVisible ? 0 : 135).setInterpolator(new AccelerateDecelerateInterpolator()).setDuration(150).start();
    mOpenChannelButton.setBackgroundTintList(ContextCompat.getColorStateList(this, isVisible ? R.color.green : R.color.colorGrey_4));
    mOpenChannelButtonsToggleView.setVisibility(isVisible ? View.GONE : View.VISIBLE);
  }

  public void home_closeOpenChannelButtons() {
    mOpenChannelButton.animate().rotation(0).setInterpolator(new AccelerateDecelerateInterpolator()).setDuration(150).start();
    mOpenChannelButton.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.green));
    mOpenChannelButtonsToggleView.setVisibility(View.GONE);
  }

  public void home_doPasteURI(View view) {
    String uri = readFromClipboard();
    if (Validators.HOST_REGEX.matcher(uri).matches()) {
      Intent intent = new Intent(getBaseContext(), OpenChannelActivity.class);
      intent.putExtra(OpenChannelActivity.EXTRA_NEW_HOST_URI, uri);
      startActivity(intent);
    } else {
      Toast.makeText(this, R.string.home_toast_openchannel_invalid, Toast.LENGTH_SHORT).show();
    }
  }

  public void home_doScanURI(View view) {
    Intent intent = new Intent(this, ScanActivity.class);
    intent.putExtra(ScanActivity.EXTRA_SCAN_TYPE, ScanActivity.TYPE_URI);
    startActivity(intent);
  }

  public void home_doRandomChannel(View view) {
    Intent intent = new Intent(getBaseContext(), OpenChannelActivity.class);
    intent.putExtra(OpenChannelActivity.EXTRA_NEW_HOST_URI, "03933884aaf1d6b108397e5efe5c86bcf2d8ca8d2f700eda99db9214fc2712b134@endurance.acinq.co:9735");
    startActivity(intent);
  }

  public void home_doCopyReceptionAddress(View view) {
    mReceivePaymentFragment.copyReceptionAddress();
  }

  @Subscribe(threadMode = ThreadMode.MAIN)
  public void handleLNBalanceEvent(LNBalanceUpdateEvent event) {
    updateBalance();
  }

  @Subscribe(threadMode = ThreadMode.MAIN)
  public void handleWalletBalanceEvent(WalletBalanceUpdateEvent event) {
    updateBalance();
  }

  @Subscribe(threadMode = ThreadMode.MAIN)
  public void handleLNPaymentFailedEvent(LNPaymentFailedEvent event) {

    Intent intent = new Intent(this, PaymentFailureActivity.class);
    intent.putExtra(PaymentFailureActivity.EXTRA_PAYMENTFAILURE_DESC, event.payment.description);
    intent.putExtra(PaymentFailureActivity.EXTRA_PAYMENTFAILURE_AMOUNT, event.payment.amountPaidMsat);
    intent.putExtra(PaymentFailureActivity.EXTRA_PAYMENTFAILURE_CAUSE, event.cause);
    startActivity(intent);
    mPaymentsListFragment.updateList();
  }

  @Subscribe(threadMode = ThreadMode.MAIN)
  public void handleLNPaymentEvent(LNPaymentEvent event) {
    Intent intent = new Intent(this, PaymentSuccessActivity.class);
    intent.putExtra(PaymentSuccessActivity.EXTRA_PAYMENTSUCCESS_DESC, event.payment.description);
    intent.putExtra(PaymentSuccessActivity.EXTRA_PAYMENTSUCCESS_AMOUNT, event.payment.amountPaidMsat);
    startActivity(intent);
    mPaymentsListFragment.updateList();
  }

  @Subscribe(threadMode = ThreadMode.MAIN)
  public void handleBitcoinPaymentEvent(BitcoinPaymentEvent event) {
    mPaymentsListFragment.updateList();
  }

  @Subscribe(threadMode = ThreadMode.MAIN)
  public void handleChannelUpdateEvent(ChannelUpdateEvent event) {
    mChannelsListFragment.updateList();
  }

  @Subscribe(threadMode = ThreadMode.MAIN)
  public void handleNewChannelSuccessfullyOpened(LNNewChannelOpenedEvent event) {
    Toast.makeText(this, getString(R.string.home_toast_openchannel_success) + event.targetNode.substring(0, 7) + "...", Toast.LENGTH_SHORT);
  }

  @Subscribe(threadMode = ThreadMode.MAIN)
  public void handleNewChannelSuccessfullyOpened(LNNewChannelFailureEvent event) {
    Toast.makeText(this, getString(R.string.home_toast_openchannel_failed) + event.cause, Toast.LENGTH_LONG);
  }

  @SuppressLint("SetTextI18n")
  private void updateBalance() {
    LNBalanceUpdateEvent lnBalanceEvent = EventBus.getDefault().getStickyEvent(LNBalanceUpdateEvent.class);
    long lnBalance = lnBalanceEvent == null ? 0 : lnBalanceEvent.total().amount();
    WalletBalanceUpdateEvent walletBalanceEvent = EventBus.getDefault().getStickyEvent(WalletBalanceUpdateEvent.class);
    long walletBalance = walletBalanceEvent == null ? 0 : package$.MODULE$.satoshi2millisatoshi(walletBalanceEvent.walletBalance).amount();
    mTotalBalanceView.setAmountMsat(new MilliSatoshi(lnBalance + walletBalance));
    mWalletBalanceView.setText(CoinUtils.formatAmountMilliBtc(new MilliSatoshi(walletBalance)) + " mBTC");
    mLNBalanceView.setText(CoinUtils.formatAmountMilliBtc(new MilliSatoshi(lnBalance)) + " mBTC");
  }

  private class HomePagerAdapter extends FragmentStatePagerAdapter {
    private final List<Fragment> mFragmentList;

    public HomePagerAdapter(FragmentManager fm, List<Fragment> fragments) {
      super(fm);
      mFragmentList = fragments;
    }

    @Override
    public Fragment getItem(int position) {
      return mFragmentList.get(position);
    }

    @Override
    public int getCount() {
      return mFragmentList.size();
    }
  }

}
