package fr.acinq.eclair.swordfish.adapters;

import android.content.Intent;
import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.text.DateFormat;

import fr.acinq.bitcoin.MilliSatoshi;
import fr.acinq.eclair.swordfish.R;
import fr.acinq.eclair.swordfish.activity.PaymentDetailsActivity;
import fr.acinq.eclair.swordfish.model.Payment;
import fr.acinq.eclair.swordfish.model.PaymentTypes;
import fr.acinq.eclair.swordfish.utils.CoinUtils;

public class PaymentItemHolder extends RecyclerView.ViewHolder implements View.OnClickListener {

  public static final String EXTRA_PAYMENT_ID = "fr.acinq.eclair.swordfish.PAYMENT_ID";
  private static final int FAILED_PAYMENT_COLOR = 0xFFCD1E56;
  private static final int PENDING_PAYMENT_COLOR = 0xFFFFB81C;
  private static final int SUCCESS_PAYMENT_COLOR = 0xFF00C28C;
  private final ImageView mPaymentIcon;
  private final TextView mDescription;
  private final TextView mStatus;
  private final TextView mDate;
  private final TextView mAmount;
  private Payment mPayment;

  public PaymentItemHolder(View itemView) {
    super(itemView);
    this.mPaymentIcon = (ImageView) itemView.findViewById(R.id.paymentitem_image);
    this.mAmount = (TextView) itemView.findViewById(R.id.paymentitem_amount_value);
    this.mStatus = (TextView) itemView.findViewById(R.id.paymentitem_status);
    this.mDescription = (TextView) itemView.findViewById(R.id.paymentitem_description);
    this.mDate = (TextView) itemView.findViewById(R.id.paymentitem_date);
    itemView.setOnClickListener(this);
  }

  @Override
  public void onClick(View v) {
    Intent intent = new Intent(v.getContext(), PaymentDetailsActivity.class);
    intent.putExtra(EXTRA_PAYMENT_ID, mPayment.getId().longValue());
    v.getContext().startActivity(intent);
  }

  public void bindPaymentItem(Payment payment) {
    this.mPayment = payment;
    try {
      if (payment.amountPaid == 0) {
        mAmount.setText(CoinUtils.formatAmountMilliBtc(new MilliSatoshi(payment.amountRequested)));
      } else {
        mAmount.setText(CoinUtils.formatAmountMilliBtc(new MilliSatoshi(payment.amountPaid)));
      }
    } catch (Exception e) {
      mAmount.setText(CoinUtils.getMilliBTCFormat().format(0));
    }
    this.mStatus.setText(payment.status);
    if ("FAILED".equals(payment.status)) {
      mStatus.setTextColor(FAILED_PAYMENT_COLOR);
    } else if ("PAID".equals(payment.status)) {
      mStatus.setTextColor(SUCCESS_PAYMENT_COLOR);
    } else {
      mStatus.setTextColor(PENDING_PAYMENT_COLOR);
    }
    if (payment.updated != null) {
      mDate.setText(DateFormat.getDateTimeInstance().format(payment.updated));
    }
    if (PaymentTypes.LN.toString().equals(payment.type)) {
      this.mDescription.setText(payment.description);
      mPaymentIcon.setImageResource(R.drawable.icon_bolt_circle_yellow);
    } else if (PaymentTypes.BTC_RECEIVED.toString().equals(payment.type)) {
      this.mDescription.setText(payment.paymentReference);
      mPaymentIcon.setImageResource(R.drawable.icon_btc_extrude_green);
    } else if (PaymentTypes.BTC_SENT.toString().equals(payment.type)) {
      this.mDescription.setText(payment.paymentReference);
      mPaymentIcon.setImageResource(R.drawable.icon_btc_extrude_red);
    }
  }

}
