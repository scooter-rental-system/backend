package com.scooterrentalkandy.spring.mappers;

import java.math.BigDecimal;
import java.util.List;
import com.scooterrentalkandy.spring.dto.BookingDtos.BookingResponse;
import com.scooterrentalkandy.spring.dto.BookingDtos.ContractResponse;
import com.scooterrentalkandy.spring.dto.BookingDtos.CustomerSummary;
import com.scooterrentalkandy.spring.dto.BookingDtos.GearLineResponse;
import com.scooterrentalkandy.spring.dto.BookingDtos.PaymentSummary;
import com.scooterrentalkandy.spring.dto.BookingDtos.RentalBreakdown;
import com.scooterrentalkandy.spring.dto.BookingDtos.ScooterSummary;
import com.scooterrentalkandy.spring.dto.BookingDtos.Stage;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.Contract;
import com.scooterrentalkandy.spring.entity.Payment;

/** The booking service works out the stage, billable hours and initial charge; this only assembles the response. */
public final class BookingMapper {

    private BookingMapper() {
    }

    public static BookingResponse toResponse(Booking b, Payment p, Stage stage, Long billableHours,
                                             RentalBreakdown breakdown, BigDecimal initialCharge) {
        var c = b.getUser();
        var s = b.getScooter();
        List<GearLineResponse> gear = b.getGear().stream()
                .map(g -> new GearLineResponse(g.getGearItem().getGearItemId(), g.getGearItem().getName(), g.getQuantity(),
                        g.getGearItem().getDailyRate()))
                .toList();
        return new BookingResponse(b.getBookingId(), b.getReference(), b.getStatus(), stage,
                new CustomerSummary(c.getUserId(), c.getFullName(), c.getEmail(), c.getPhone()),
                new ScooterSummary(s.getScooterId(), s.getCode(), s.getModel(), s.getPlateNumber(), s.getImageUrl(),
                        s.getDailyRate(), s.getHourlyRate(), s.getPerKmRate()),
                b.getStartTime(), b.getEndTime(), billableHours, breakdown, b.getDistanceKm(), b.getTotalCost(), b.getEndedAt(),
                b.getPendingDistanceKm(), b.getPendingTotalCost(), initialCharge, gear,
                p == null ? null : new PaymentSummary(p.getStatus(), p.getAmount(), p.getBalanceDue(),
                        p.getCardLast4(), p.getFailureReason(), p.getPaidAt(), p.getMethod(), p.getRefundDue()),
                b.getPickupLocation(), b.getDropLocation(), b.getNotes(), b.isContractSigned(), b.getCancelledAt(),
                b.getCreatedAt());
    }

    public static ContractResponse toResponse(Contract c) {
        return new ContractResponse(c.getContractId(), c.getContractNumber(), c.getTermsVersion(), c.getTermsText(),
                c.isSigned(), c.getSignedName(), c.getSignedAt());
    }
}
