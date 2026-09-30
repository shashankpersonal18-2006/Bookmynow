let bookingModal;
let activeMoviePrice = 0;

// High quality poster fallback imagery for catalog
const posterImages = {
    "Inception": "https://images.unsplash.com/photo-1536440136628-849c177e76a1?q=80&w=800&auto=format&fit=crop",
    "Interstellar": "https://images.unsplash.com/photo-1451187580459-43490279c0fa?q=80&w=800&auto=format&fit=crop",
    "The Dark Knight": "https://images.unsplash.com/photo-1509198397868-475647b2a1e5?q=80&w=800&auto=format&fit=crop"
};

document.addEventListener("DOMContentLoaded", () => {
    bookingModal = new bootstrap.Modal(document.getElementById('bookingModal'));
    fetchMovies();
    fetchBookings();

    document.getElementById("booking-form").addEventListener("submit", handleBookingSubmit);
    document.getElementById("ticket-count").addEventListener("input", updateCalculatedTotal);
});

// Fetch movie list from GET /api/movies
async function fetchMovies() {
    try {
        const response = await fetch('/api/movies');
        const movies = await response.json();
        
        const container = document.getElementById('movie-list');
        container.innerHTML = '';

        movies.forEach(movie => {
            const imageSrc = posterImages[movie.title] || "https://images.unsplash.com/photo-1489599849927-2ee91cede3ba?q=80&w=800&auto=format&fit=crop";
            
            const cardHtml = `
                <div class="col">
                    <div class="card movie-card h-100 text-light overflow-hidden shadow">
                        <div class="poster-wrapper">
                            <img src="${imageSrc}" class="poster-img" alt="${movie.title}">
                            <span class="badge genre-badge border border-light border-opacity-25 text-light px-2.5 py-1.5 rounded-pill">${movie.genre}</span>
                        </div>
                        <div class="card-body d-flex flex-column justify-content-between p-4">
                            <div>
                                <h3 class="h5 card-title fw-bold mb-2">${movie.title}</h3>
                                <p class="text-secondary small mb-3">Ticket Price: <span class="text-light fw-bold fs-6">$${movie.price}</span></p>
                            </div>
                            <button class="btn btn-danger w-100 fw-semibold rounded-3 py-2 shadow-sm" onclick="openBookingModal('${movie.title}', ${movie.price})">
                                <i class="fa-solid fa-ticket me-1"></i> Book Now
                            </button>
                        </div>
                    </div>
                </div>
            `;
            container.insertAdjacentHTML('beforeend', cardHtml);
        });
    } catch (err) {
        showAlert('danger', 'Failed to load movie catalog from backend.');
    }
}

// Fetch database records from GET /api/bookings
async function fetchBookings() {
    try {
        const response = await fetch('/api/bookings');
        const bookings = await response.json();

        const tbody = document.getElementById('bookings-table-body');
        tbody.innerHTML = '';

        if (bookings.length === 0) {
            tbody.innerHTML = `
                <tr>
                    <td colspan="5" class="text-center text-secondary py-5">
                        <i class="fa-solid fa-folder-open d-block fs-3 mb-2 opacity-50"></i>
                        No booking records currently stored in SQLite database.
                    </td>
                </tr>
            `;
            return;
        }

        bookings.forEach(item => {
            const row = `
                <tr class="border-secondary border-opacity-10">
                    <td class="ps-4 fw-bold text-danger">#${item.id}</td>
                    <td class="fw-semibold">${escapeHtml(item.name)}</td>
                    <td><span class="badge bg-secondary bg-opacity-20 text-light border border-secondary border-opacity-25 px-2 py-1">${escapeHtml(item.movie)}</span></td>
                    <td class="text-center fw-bold">${item.tickets}</td>
                    <td class="text-secondary small"><i class="fa-regular fa-clock me-1"></i>${escapeHtml(item.showtime)}</td>
                </tr>
            `;
            tbody.insertAdjacentHTML('beforeend', row);
        });
    } catch (err) {
        showAlert('danger', 'Failed to load booking history.');
    }
}

function openBookingModal(movieTitle, price) {
    activeMoviePrice = price;
    document.getElementById('selected-movie').value = movieTitle;
    document.getElementById('user-name').value = '';
    document.getElementById('ticket-count').value = 1;
    updateCalculatedTotal();
    bookingModal.show();
}

function updateCalculatedTotal() {
    const tickets = parseInt(document.getElementById('ticket-count').value) || 1;
    const total = tickets * activeMoviePrice;
    document.getElementById('total-price').textContent = `$${total}`;
}

// Create new reservation via POST /api/book
async function handleBookingSubmit(e) {
    e.preventDefault();

    const bookingData = {
        movie: document.getElementById('selected-movie').value,
        name: document.getElementById('user-name').value,
        tickets: document.getElementById('ticket-count').value,
        showtime: document.getElementById('showtime-select').value
    };

    try {
        const response = await fetch('/api/book', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(bookingData)
        });

        const result = await response.json();
        if (result.status === 'success') {
            bookingModal.hide();
            showAlert('success', 'Reservation stored successfully in SQLite database!');
            fetchBookings();
        } else {
            showAlert('danger', 'Booking failed: ' + (result.message || 'Error occurred'));
        }
    } catch (err) {
        showAlert('danger', 'Server error. Could not save booking.');
    }
}

// Clear all database records via DELETE /api/bookings
async function clearAllBookings() {
    if (!confirm("Are you sure you want to clear all reservation entries from the SQLite database?")) return;

    try {
        const response = await fetch('/api/bookings', { method: 'DELETE' });
        const result = await response.json();

        if (result.status === 'cleared') {
            showAlert('warning', 'All reservation records removed from database.');
            fetchBookings();
        }
    } catch (err) {
        showAlert('danger', 'Failed to perform database clear command.');
    }
}

function showAlert(type, message) {
    const alertBox = document.getElementById('alert-box');
    const alertMsg = document.getElementById('alert-msg');
    
    alertBox.className = `alert alert-${type} custom-alert border-0 text-light shadow-lg d-flex align-items-center justify-content-between`;
    alertMsg.innerHTML = message;
    alertBox.classList.remove('d-none');

    setTimeout(() => { hideAlert(); }, 4000);
}

function hideAlert() {
    document.getElementById('alert-box').classList.add('d-none');
}

function escapeHtml(str) {
    return String(str).replace(/[&<>"']/g, function(m) {
        return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;' }[m];
    });
}