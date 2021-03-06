/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.wallet.actors;

import com.google.common.base.Strings;

import org.greenrobot.eventbus.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import akka.actor.ActorRef;
import akka.actor.Terminated;
import akka.actor.UntypedActor;
import fr.acinq.bitcoin.ByteVector32;
import fr.acinq.bitcoin.Crypto;
import fr.acinq.bitcoin.Satoshi;
import fr.acinq.eclair.CltvExpiryDelta;
import fr.acinq.eclair.MilliSatoshi;
import fr.acinq.eclair.ShortChannelId;
import fr.acinq.eclair.channel.CLOSED$;
import fr.acinq.eclair.channel.CLOSING$;
import fr.acinq.eclair.channel.ChannelCreated;
import fr.acinq.eclair.channel.ChannelErrorOccurred;
import fr.acinq.eclair.channel.ChannelIdAssigned;
import fr.acinq.eclair.channel.ChannelRestored;
import fr.acinq.eclair.channel.ChannelSignatureReceived;
import fr.acinq.eclair.channel.ChannelSignatureSent;
import fr.acinq.eclair.channel.ChannelStateChanged;
import fr.acinq.eclair.channel.Commitments;
import fr.acinq.eclair.channel.DATA_CLOSING;
import fr.acinq.eclair.channel.HasCommitments;
import fr.acinq.eclair.channel.LocalChannelUpdate;
import fr.acinq.eclair.channel.LocalCommit;
import fr.acinq.eclair.channel.LocalCommitConfirmed;
import fr.acinq.eclair.channel.LocalError;
import fr.acinq.eclair.channel.NORMAL$;
import fr.acinq.eclair.channel.OFFLINE$;
import fr.acinq.eclair.channel.RemoteCommit;
import fr.acinq.eclair.channel.RemoteError;
import fr.acinq.eclair.channel.SHUTDOWN$;
import fr.acinq.eclair.channel.ShortChannelIdAssigned;
import fr.acinq.eclair.channel.WAIT_FOR_INIT_INTERNAL$;
import fr.acinq.eclair.channel.WaitingForRevocation;
import fr.acinq.eclair.db.BackupCompleted$;
import fr.acinq.eclair.payment.PaymentFailed;
import fr.acinq.eclair.payment.PaymentFailure;
import fr.acinq.eclair.payment.PaymentFailure$;
import fr.acinq.eclair.payment.PaymentReceived;
import fr.acinq.eclair.payment.PaymentRequest;
import fr.acinq.eclair.payment.PaymentSent;
import fr.acinq.eclair.router.SyncProgress;
import fr.acinq.eclair.transactions.DirectedHtlc;
import fr.acinq.eclair.transactions.IncomingHtlc;
import fr.acinq.eclair.transactions.OutgoingHtlc;
import fr.acinq.eclair.wallet.DBHelper;
import fr.acinq.eclair.wallet.events.ClosingChannelNotificationEvent;
import fr.acinq.eclair.wallet.events.LNPaymentFailedEvent;
import fr.acinq.eclair.wallet.events.LNPaymentSuccessEvent;
import fr.acinq.eclair.wallet.events.ReceivedLNPaymentNotificationEvent;
import fr.acinq.eclair.wallet.models.ClosingType;
import fr.acinq.eclair.wallet.models.LightningPaymentError;
import fr.acinq.eclair.wallet.models.LocalChannel;
import fr.acinq.eclair.wallet.models.Payment;
import fr.acinq.eclair.wallet.models.PaymentDirection;
import fr.acinq.eclair.wallet.models.PaymentStatus;
import fr.acinq.eclair.wallet.models.PaymentType;
import fr.acinq.eclair.wallet.services.ChannelsBackupWorker;
import fr.acinq.eclair.wallet.utils.Constants;
import fr.acinq.eclair.wallet.utils.WalletUtils;
import scala.collection.Iterator;
import scala.collection.JavaConverters;
import scala.collection.Seq;
import scala.util.Either;
import scodec.bits.ByteVector;

/**
 * This actor handles the messages sent by the Eclair node.
 */
public class NodeSupervisor extends UntypedActor {

  private final static Logger log = LoggerFactory.getLogger(NodeSupervisor.class);
  private DBHelper dbHelper;
  private ActorRef paymentRefreshScheduler;
  private ActorRef channelsRefreshScheduler;
  private ActorRef balanceRefreshScheduler;
  private final String seedHash;
  private final ByteVector32 backupKey;

  public NodeSupervisor(final DBHelper dbHelper, final String seedHash, final ByteVector32 backupKey,
                        final ActorRef paymentRefreshScheduler, final ActorRef channelsRefreshScheduler, final ActorRef balanceRefreshScheduler) {
    this.dbHelper = dbHelper;
    this.seedHash = seedHash;
    this.backupKey = backupKey;
    this.paymentRefreshScheduler = paymentRefreshScheduler;
    this.channelsRefreshScheduler = channelsRefreshScheduler;
    this.balanceRefreshScheduler = balanceRefreshScheduler;
  }

  private static Map<ActorRef, LocalChannel> activeChannelsMap = new ConcurrentHashMap<>();

  public static Map<ActorRef, LocalChannel> getChannelsMap() {
    return activeChannelsMap;
  }

  public static MilliSatoshi getChannelsBalance() {
    long total = 0;
    for (LocalChannel c : NodeSupervisor.getChannelsMap().values()) {
      if (c.fundsAreAccountedFor()) {
        total += c.getBalanceMsat();
      }
    }
    return new MilliSatoshi(total);
  }

  public static scala.collection.immutable.List<scala.collection.immutable.List<PaymentRequest.ExtraHop>> getRoutes() {
    final List<scala.collection.immutable.List<PaymentRequest.ExtraHop>> routes = new ArrayList<>();
    final Set<String> peersInRoute = new HashSet<>();
    for (LocalChannel channel : getChannelsMap().values()) {
      if (!Strings.isNullOrEmpty(channel.getShortChannelId()) && !peersInRoute.contains(channel.getPeerNodeId()) && routes.size() < 5
        && channel.receivedChannelUpdate()) {
        routes.add(getExtraHops(channel));
        peersInRoute.add(channel.getPeerNodeId());
      }
    }
    return JavaConverters.asScalaIteratorConverter(routes.iterator()).asScala().toList();
  }

  private static scala.collection.immutable.List<PaymentRequest.ExtraHop> getExtraHops(final LocalChannel channel) {
    final List<PaymentRequest.ExtraHop> hops = new ArrayList<>();
    final PaymentRequest.ExtraHop hop = new PaymentRequest.ExtraHop(
      Crypto.PublicKey$.MODULE$.apply(ByteVector.view(Hex.decode(channel.getPeerNodeId())), false),
      ShortChannelId.apply(channel.getShortChannelId()),
      new MilliSatoshi(channel.feeBaseMsat),
      channel.feeProportionalMillionths,
      new CltvExpiryDelta(channel.cltvExpiryDelta));
    hops.add(hop);
    return JavaConverters.asScalaIteratorConverter(hops.iterator()).asScala().toList();
  }

  private static LocalChannel getOrCreateChannel(ActorRef ref) {
    return activeChannelsMap.containsKey(ref) ? activeChannelsMap.get(ref) : new LocalChannel();
  }

  @Override
  public void onReceive(final Object message) {
    if (message instanceof ChannelCreated) {
      final ChannelCreated event = (ChannelCreated) message;
      final LocalChannel c = getOrCreateChannel(event.channel());
      c.setChannelId(event.temporaryChannelId().toString());
      c.setPeerNodeId(event.remoteNodeId().toString());
      c.isFunder = event.isFunder();
      activeChannelsMap.put(event.channel(), c);
      context().watch(event.channel());
      channelsRefreshScheduler.tell(Constants.REFRESH, null);
    } else if (message instanceof ChannelRestored) {
      final ChannelRestored event = (ChannelRestored) message;
      final LocalChannel c = getOrCreateChannel(event.channel());
      c.setChannelId(event.channelId().toString());
      c.setPeerNodeId(event.remoteNodeId().toString());
      c.isFunder = event.isFunder();
      final Iterator<DirectedHtlc> it = event.currentData().commitments().localCommit().spec().htlcs().iterator();
      int htlcsCount = 0;
      while (it.hasNext()) {
        final DirectedHtlc htlc = it.next();
        htlcsCount++;
        if (htlc instanceof OutgoingHtlc) {
          final String htlcPaymentHash = htlc.add().paymentHash().toString();
          final Payment p = dbHelper.getPayment(htlcPaymentHash, PaymentType.BTC_LN);
          if (p != null && p.getStatus() == PaymentStatus.INIT) {
            dbHelper.updatePaymentPending(p);
            paymentRefreshScheduler.tell(Constants.REFRESH, null);
          }
        }
      }
      c.htlcsInFlightCount = htlcsCount;
      c.sendableBalanceMsat = event.currentData().commitments().availableBalanceForSend().toLong();

      // restore data from DB that were sent only once by the node and may have be persisted
      final LocalChannel channelInDB = dbHelper.getLocalChannel(c.getChannelId());
      if (channelInDB != null) {
        c.setClosingErrorMessage(channelInDB.getClosingErrorMessage());
        c.setRefundAtBlock(channelInDB.getRefundAtBlock());
      }

      activeChannelsMap.put(event.channel(), c);
      context().watch(event.channel());
      balanceRefreshScheduler.tell(Constants.REFRESH, null);
      channelsRefreshScheduler.tell(Constants.REFRESH, null);
    }
    // ---- channel id assigned
    else if (message instanceof ChannelIdAssigned) {
      final ChannelIdAssigned event = (ChannelIdAssigned) message;
      final LocalChannel c = activeChannelsMap.get(event.channel());
      if (c != null) {
        c.setChannelId(event.channelId().toString());
        dbHelper.saveLocalChannel(c);
      }
    }
    // ---- short channel id assigned
    else if (message instanceof ShortChannelIdAssigned) {
      final ShortChannelIdAssigned event = (ShortChannelIdAssigned) message;
      final LocalChannel c = activeChannelsMap.get(event.channel());
      if (c != null) {
        c.setShortChannelId(event.shortChannelId().toString());
      }
    }
    // ---- we sent a channel sig => update corresponding payment to PENDING in app's DB
    else if (message instanceof ChannelSignatureSent) {
      final ChannelSignatureSent event = (ChannelSignatureSent) message;
      final Either<WaitingForRevocation, Crypto.PublicKey> nextCommitInfo = event.commitments().remoteNextCommitInfo();
      if (nextCommitInfo.isLeft()) {
        final RemoteCommit commit = nextCommitInfo.left().get().nextRemoteCommit();
        final Iterator<DirectedHtlc> htlcsIterator = commit.spec().htlcs().iterator();
        while (htlcsIterator.hasNext()) {
          final DirectedHtlc h = htlcsIterator.next();
          log.debug("sig sent for htlc={}", h);
          // if htlc is outbound, move payment to PENDING (IN from remote commit means that payment is sent)
          if (h instanceof IncomingHtlc) {
            final String htlcPaymentHash = h.add().paymentHash().toString();
            final Payment p = dbHelper.getPayment(htlcPaymentHash, PaymentType.BTC_LN);
            if (p != null && p.getStatus() == PaymentStatus.INIT) {
              dbHelper.updatePaymentPending(p);
              paymentRefreshScheduler.tell(Constants.REFRESH, null);
            }
          }
        }
      }
    }
    // ---- balance update, only for the channels we know
    else if (message instanceof ChannelSignatureReceived) {
      final ChannelSignatureReceived event = (ChannelSignatureReceived) message;
      final LocalChannel c = activeChannelsMap.get(event.channel());
      if (c != null) {
        final LocalCommit localCommit = event.commitments().localCommit();
        c.setChannelReserveSat(event.commitments().localParams().channelReserve().toLong());
        c.setMinimumHtlcAmountMsat(event.commitments().localParams().htlcMinimum().toLong());
        c.htlcsInFlightCount = localCommit.spec().htlcs().iterator().size();
        c.sendableBalanceMsat = event.commitments().availableBalanceForSend().toLong();
        c.setBalanceMsat(localCommit.spec().toLocal().toLong());
        c.setCapacityMsat(localCommit.spec().totalFunds().toLong());
        balanceRefreshScheduler.tell(Constants.REFRESH, null);
        channelsRefreshScheduler.tell(Constants.REFRESH, null);
      }
    }
    // ---- backup file must be saved (on disk/gdrive) after backup by eclair-core is done. We save the .bak file.
    else if (message instanceof BackupCompleted$) {
      log.debug("received BackupCompleted event, scheduling backup work");
      ChannelsBackupWorker.scheduleWorkASAP(seedHash, backupKey);
    }
    // ---- network map syncing
    else if (message instanceof SyncProgress) {
      EventBus.getDefault().post(message);
    }
    // ---- channel has been terminated
    else if (message instanceof Terminated) {
      final Terminated event = (Terminated) message;
      final LocalChannel c = activeChannelsMap.get(event.getActor());
      if (c != null) {
        dbHelper.channelTerminated(c.getChannelId());
      }
      activeChannelsMap.remove(event.getActor());
      balanceRefreshScheduler.tell(Constants.REFRESH, null);
      channelsRefreshScheduler.tell(Constants.REFRESH, null);
    }
    // ---- channel is in error
    else if (message instanceof ChannelErrorOccurred) {
      final ChannelErrorOccurred event = (ChannelErrorOccurred) message;
      final LocalChannel c = activeChannelsMap.get(event.channel());
      if (c != null && event.isFatal()) {
        if (event.error() instanceof LocalError) {
          final LocalError localError = (LocalError) event.error();
          if (localError.t() != null) {
            c.setClosingErrorMessage(localError.t().getMessage());
            dbHelper.saveLocalChannel(c);
          }
        } else if (event.error() instanceof RemoteError) {
          final RemoteError remoteError = (RemoteError) event.error();
          if (fr.acinq.eclair.package$.MODULE$.isAsciiPrintable(remoteError.e().data())) {
            c.setClosingErrorMessage(WalletUtils.toAscii(remoteError.e().data()));
          } else {
            c.setClosingErrorMessage(remoteError.e().data().toString());
          }
          dbHelper.saveLocalChannel(c);
        }
      }
    }
    // ---- channel is closing and we know when the main output is refunded
    else if (message instanceof LocalCommitConfirmed) {
      final LocalCommitConfirmed event = (LocalCommitConfirmed) message;
      log.debug("received local commit confirmed for channel {}, refund at block {}", event.channelId(), event.refundAtBlock());
      final LocalChannel c = activeChannelsMap.get(event.channel());
      if (c != null) {
        c.setRefundAtBlock(event.refundAtBlock());
        dbHelper.saveLocalChannel(c);
        channelsRefreshScheduler.tell(Constants.REFRESH, null);
      }
    }
    // ---- channel state changed
    else if (message instanceof ChannelStateChanged) {
      final ChannelStateChanged event = (ChannelStateChanged) message;
      final LocalChannel c = activeChannelsMap.get(event.channel());
      if (c != null) {

        if (event.currentData() instanceof DATA_CLOSING) {
          final DATA_CLOSING d = (DATA_CLOSING) event.currentData();
          if (!d.mutualClosePublished().isEmpty() && d.localCommitPublished().isEmpty()
            && d.remoteCommitPublished().isEmpty() && d.revokedCommitPublished().isEmpty()) {
            c.setClosingType(ClosingType.MUTUAL);
            c.mainClosingTxs = JavaConverters.seqAsJavaListConverter(d.mutualClosePublished()).asJava();
          } else if (d.mutualClosePublished().isEmpty() && d.localCommitPublished().isEmpty()
            && d.remoteCommitPublished().isDefined() && d.revokedCommitPublished().isEmpty()) {
            c.setClosingType(ClosingType.REMOTE);
            c.mainClosingTxs = Collections.singletonList(d.remoteCommitPublished().get().commitTx());
          } else if (d.mutualClosePublished().isEmpty() && d.localCommitPublished().isDefined()
            && d.remoteCommitPublished().isEmpty() && d.revokedCommitPublished().isEmpty()) {
            c.setClosingType(ClosingType.LOCAL);
            c.mainClosingTxs = Collections.singletonList(d.localCommitPublished().get().commitTx());
            c.mainDelayedClosingTx = d.localCommitPublished().get().claimMainDelayedOutputTx();
          } else {
            c.setClosingType(ClosingType.OTHER);
          }

          // Don't show the notification if state goes straight from WAIT_FOR_INIT_INTERNAL to CLOSING
          // Otherwise the notification would show up each time the wallet is started and the channel is
          // still closing, even though the user has already been alerted the last time he used the app.
          // Same thing for CLOSING -> CLOSED
          if (!CLOSED$.MODULE$.toString().equals(event.currentState().toString())
            && !WAIT_FOR_INIT_INTERNAL$.MODULE$.toString().equals(event.previousState().toString())) {
            final MilliSatoshi balanceLeft = d.commitments().localCommit().spec().toLocal();
            EventBus.getDefault().post(new ClosingChannelNotificationEvent(
              c.getChannelId(), c.getPeerNodeId(), ClosingType.LOCAL.equals(c.getClosingType()), balanceLeft, c.getToSelfDelayBlocks()));
          }
        }
        c.state = event.currentState().toString();
        if (event.currentData() instanceof HasCommitments) {
          final Commitments commitments = ((HasCommitments) event.currentData()).commitments();
          c.setLocalFeatures(commitments.remoteParams().features().toByteVector().toHex());
          c.setToSelfDelayBlocks(commitments.remoteParams().toSelfDelay().toInt());
          c.remoteToSelfDelayBlocks = commitments.localParams().toSelfDelay().toInt();
          c.htlcsInFlightCount = commitments.localCommit().spec().htlcs().iterator().size();
          c.setChannelReserveSat(commitments.localParams().channelReserve().toLong());
          c.setMinimumHtlcAmountMsat(commitments.localParams().htlcMinimum().toLong());
          c.setFundingTxId(commitments.commitInput().outPoint().txid().toString());
          c.sendableBalanceMsat = commitments.availableBalanceForSend().toLong();
          c.setBalanceMsat(commitments.localCommit().spec().toLocal().toLong());
          c.setCapacityMsat(commitments.localCommit().spec().totalFunds().toLong());
        }

        activeChannelsMap.put(event.channel(), c);
        dbHelper.saveLocalChannel(c);
        balanceRefreshScheduler.tell(Constants.REFRESH, null);
        channelsRefreshScheduler.tell(Constants.REFRESH, null);
      }
    }
    // ---- local channels updates (fees)
    else if (message instanceof LocalChannelUpdate) {
      final LocalChannelUpdate event = (LocalChannelUpdate) message;
      final LocalChannel c = activeChannelsMap.get(event.channel());
      if (event.channelUpdate() != null && c != null) {
        c.feeBaseMsat = event.channelUpdate().feeBaseMsat().toLong();
        c.feeProportionalMillionths = event.channelUpdate().feeProportionalMillionths();
        c.cltvExpiryDelta = event.channelUpdate().cltvExpiryDelta().toInt();
      }
    }
    // ---- failed outbound payment
    else if (message instanceof PaymentFailed) {
      final PaymentFailed event = (PaymentFailed) message;
      final Payment paymentInDB = dbHelper.getPayment(event.paymentHash().toString(), PaymentType.BTC_LN);
      if (paymentInDB != null) {
        dbHelper.updatePaymentFailed(paymentInDB);
        // extract failure cause to generate a pretty error message
        final ArrayList<LightningPaymentError> errorList = new ArrayList<>();
        final Seq<PaymentFailure> failures = PaymentFailure$.MODULE$.transformForUser(event.failures());
        if (failures.size() > 0) {
          for (int i = 0; i < failures.size(); i++) {
            errorList.add(LightningPaymentError.generateDetailedErrorCause(failures.apply(i)));
          }
        }
        EventBus.getDefault().post(new LNPaymentFailedEvent(paymentInDB.getReference(), paymentInDB.getDescription(), false, null, errorList));
        paymentRefreshScheduler.tell(Constants.REFRESH, null);
      } else {
        log.debug("received and ignored an unknown PaymentFailed event with hash={}", event.paymentHash().toString());
      }
    }
    // ---- successful outbound payment
    else if (message instanceof PaymentSent) {
      final PaymentSent event = (PaymentSent) message;
      final Payment paymentInDB = dbHelper.getPayment(event.paymentHash().toString(), PaymentType.BTC_LN);
      if (paymentInDB != null) {
        dbHelper.updatePaymentPaid(paymentInDB, event.amountWithFees().toLong(), event.feesPaid().toLong(), event.paymentPreimage().toString());
        EventBus.getDefault().post(new LNPaymentSuccessEvent(paymentInDB));
        paymentRefreshScheduler.tell(Constants.REFRESH, null);
      } else {
        log.debug("received and ignored an unknown PaymentSucceeded event with hash={}", event.paymentHash().toString());
      }
    }
    // ---- successful inbound payment
    else if (message instanceof fr.acinq.eclair.payment.PaymentReceived) {
      final PaymentReceived pr = (PaymentReceived) message;
      final String paymentHash = pr.paymentHash().toString();
      final Payment paymentInDB = dbHelper.getPayment(paymentHash, PaymentType.BTC_LN);
      log.debug("received a successful payment with hash={}", paymentHash);
      if (paymentInDB != null) {
        dbHelper.updatePaymentReceived(paymentInDB, pr.amount().toLong());
      } else {
        final Payment p = new Payment();
        p.setType(PaymentType.BTC_LN);
        p.setDirection(PaymentDirection.RECEIVED);
        p.setReference(paymentHash);
        p.setAmountPaidMsat(pr.amount().toLong());
        p.setStatus(PaymentStatus.PAID);
        p.setUpdated(new Date());
        dbHelper.insertOrUpdatePayment(p);
      }
      EventBus.getDefault().post(new LNPaymentSuccessEvent(paymentInDB));
      EventBus.getDefault().post(new ReceivedLNPaymentNotificationEvent(paymentHash, paymentInDB == null ? null : paymentInDB.getDescription(), pr.amount()));
      paymentRefreshScheduler.tell(Constants.REFRESH, null);
    }
  }

  /**
   * Checks if the wallet has at least 1 NORMAL channel with enough balance (including channel reserve).
   */
  public static boolean hasNormalChannelsWithBalance(final long requiredBalanceMsat) {
    for (LocalChannel d : activeChannelsMap.values()) {
      if ((NORMAL$.MODULE$.toString().equals(d.state) || OFFLINE$.MODULE$.toString().equals(d.state))
        & d.getBalanceMsat() > requiredBalanceMsat + MilliSatoshi.toMilliSatoshi(new Satoshi(d.getChannelReserveSat())).toLong()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Optimistically estimates the maximum amount that this node can receive. OFFLINE/SYNCING channels' balances are accounted
   * for in order to smooth this estimation if the connection is flaky. With MPP, this amount aggregate the incoming capacity all channels.
   */
  public static MilliSatoshi getMaxReceivable() {
    long totalMsat = 0;
    for (LocalChannel d : activeChannelsMap.values()) {
      if (d.fundsAreUsable()) {
        totalMsat += d.getReceivableMsat();
      }
    }
    return new MilliSatoshi(totalMsat);
  }

  public final static int MIN_REMOTE_TO_SELF_DELAY = 2016;

  /**
   * Checks if the node in its current state can receive lightning payments. If the node has one or more channels with a
   * low to self delay, will return false.
   */
  public static boolean canReceivePayments() {
    for (LocalChannel d : activeChannelsMap.values()) {
      if (d.remoteToSelfDelayBlocks < MIN_REMOTE_TO_SELF_DELAY
        && !(CLOSING$.MODULE$.toString().equals(d.state) || SHUTDOWN$.MODULE$.toString().equals(d.state) || CLOSED$.MODULE$.toString().equals(d.state))) {
        log.debug("channel {} in state {} has remote toSelfDelay={}, node cannot receive ln payment", d.getChannelId(), d.state, d.remoteToSelfDelayBlocks);
        return false;
      }
    }
    return true;
  }

  /**
   * Return true if one channel is normal, otherwise returns false.
   */
  public static boolean hasOneNormalChannel() {
    if (activeChannelsMap.isEmpty()) return false;
    for (LocalChannel d : activeChannelsMap.values()) {
      if (NORMAL$.MODULE$.toString().equals(d.state)) {
        return true;
      }
    }
    return false;
  }

  public static Map.Entry<ActorRef, LocalChannel> getChannelFromId(String channelId) {
    for (Map.Entry<ActorRef, LocalChannel> c : NodeSupervisor.getChannelsMap().entrySet()) {
      if (c.getValue().getChannelId().equals(channelId)) {
        return c;
      }
    }
    return null;
  }
}
